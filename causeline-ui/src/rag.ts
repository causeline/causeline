// SPDX-License-Identifier: Apache-2.0
import type { SpanRow } from './api';

/** AI attributes shown in the RAG panel instead of the plain attribute list. */
export const AI_PANEL_ATTRIBUTES = new Set([
  'gen_ai.input.messages',
  'gen_ai.output.messages',
  'gen_ai.system_instructions',
  'causeline.ai.embedding.input',
  'causeline.ai.documents',
  'causeline.ai.documents.content',
  'causeline.ai.documents.count',
  'causeline.ai.input.chars',
  'causeline.ai.output.chars',
  'causeline.ai.low_score',
  'causeline.ai.context_limit',
  'db.vector.query.content',
  'db.vector.query.filter',
]);

const WITHHELD = '[withheld on export]';

/** Whether a chunk reached the prompt. Unknown when the text to compare isn't there, so nothing is claimed. */
export type Placement = 'in-prompt' | 'not-in-prompt' | 'unknown';

export interface RagChunk {
  /** The number shown on this chunk and where it landed in the prompt: the same text has one number. */
  ref: number;
  id: string;
  rank: number;
  score?: number;
  /** The document's full length; the text below may be shorter. */
  chars: number;
  /** How much of it was kept, counted as captured so line endings don't look like a cut. */
  keptChars: number;
  text?: string;
  source?: string;
  placement: Placement;
}

export interface RagSearch {
  span: SpanRow;
  query?: string;
  filter?: string;
  topK?: string;
  threshold?: string;
  store?: string;
  collection?: string;
  /** How many documents came back, which can be more than were kept. */
  count: number;
  chunks: RagChunk[];
  textWithheld: boolean;
}

export interface PromptSegment {
  text: string;
  /** Set when this part of the prompt is a chunk. */
  ref?: number;
}

export interface RagMessage {
  role: string;
  segments: PromptSegment[];
}

export interface RagAnswer {
  text: string;
  finishReason?: string;
}

export interface RagCall {
  span: SpanRow;
  model?: string;
  messages: RagMessage[];
  answers: RagAnswer[];
  /** The prompt is longer than what was kept, so a chunk missing from it may just have been cut. */
  promptCut: boolean;
  answerCut: boolean;
  promptWithheld: boolean;
  answerWithheld: boolean;
}

export interface RagView {
  searches: RagSearch[];
  call?: RagCall;
}

export interface TokenUsage {
  input?: number;
  output?: number;
  cached?: number;
  total?: number;
}

/** Replaying such a trace calls the model again, which costs money and gives a different answer. */
export const callsModel = (spans: SpanRow[]) => spans.some((span) => span.kind === 'AI_MODEL');

export function isChat(span: SpanRow): boolean {
  return span.kind === 'AI_MODEL' && span.attributes['gen_ai.operation.name'] === 'chat';
}

export function tokenUsage(span: SpanRow): TokenUsage | undefined {
  const number = (key: string) => {
    const value = Number(span.attributes[key]);
    return span.attributes[key] !== undefined && Number.isFinite(value) ? value : undefined;
  };
  const usage: TokenUsage = {
    input: number('gen_ai.usage.input_tokens'),
    output: number('gen_ai.usage.output_tokens'),
    cached: number('gen_ai.usage.cache_read.input_tokens'),
    total: number('gen_ai.usage.total_tokens'),
  };
  return usage.input === undefined && usage.output === undefined ? undefined : usage;
}

/** Spring AI joins chunks with the platform's line separator, so CRLF must not stop a match. */
const normalize = (text: string) => text.replace(/\r\n/g, '\n');

function parse(value: string | undefined): unknown {
  if (value === undefined || value === WITHHELD) {
    return undefined;
  }
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

const list = (value: unknown): Record<string, unknown>[] =>
  Array.isArray(value) ? value.filter((v): v is Record<string, unknown> => typeof v === 'object' && v !== null) : [];

const optional = (value: unknown) => (typeof value === 'string' ? value : undefined);

interface RawMessage {
  role: string;
  text: string;
  chars: number;
}

const captured = (role: string, text: string): RawMessage => ({ role, text: normalize(text), chars: text.length });

function messageText(message: Record<string, unknown>): string {
  return list(message.parts).map((part) => optional(part.content) ?? '').join('');
}

function rawMessages(span: SpanRow): RawMessage[] {
  const system = list(parse(span.attributes['gen_ai.system_instructions'])).map((part) =>
    captured('system', optional(part.content) ?? ''),
  );
  const others = list(parse(span.attributes['gen_ai.input.messages'])).map((message) =>
    captured(optional(message.role) ?? 'user', messageText(message)),
  );
  return [...system, ...others];
}

/** Which chat call used a search: the first one to start after it ended, in the same service. */
function callAfter(search: SpanRow, chats: SpanRow[]): SpanRow | undefined {
  const end = search.offsetNanos + search.durationNanos;
  return chats.find((chat) => chat.source === search.source && chat.offsetNanos >= end);
}

/** Each chunk once, at its first place: a later chunk overlapping an earlier one stays plain text. */
export function promptSegments(text: string, chunks: { ref: number; text: string }[]): PromptSegment[] {
  const hits = chunks
    .map((chunk) => ({ ref: chunk.ref, start: text.indexOf(chunk.text), length: chunk.text.length }))
    .filter((hit) => hit.start >= 0 && hit.length > 0)
    .sort((a, b) => a.start - b.start || b.length - a.length);
  const segments: PromptSegment[] = [];
  let at = 0;
  for (const hit of hits) {
    if (hit.start < at) {
      continue;
    }
    if (hit.start > at) {
      segments.push({ text: text.slice(at, hit.start) });
    }
    segments.push({ text: text.slice(hit.start, hit.start + hit.length), ref: hit.ref });
    at = hit.start + hit.length;
  }
  if (at < text.length || segments.length === 0) {
    segments.push({ text: text.slice(at) });
  }
  return segments;
}

function buildCall(chat: SpanRow, chunks: { ref: number; text: string }[]): { call: RagCall; raw: RawMessage[] } {
  const raw = rawMessages(chat);
  const a = chat.attributes;
  const fullChars = Number(a['causeline.ai.input.chars'] ?? 0);
  const output = list(parse(a['gen_ai.output.messages']));
  const answers = output.map((message) => ({
    text: normalize(messageText(message)),
    finishReason: optional(message.finish_reason),
  }));
  const fullAnswer = Number(a['causeline.ai.output.chars'] ?? 0);
  const keptPrompt = raw.reduce((sum, message) => sum + message.chars, 0);
  const keptAnswer = output.reduce((sum, message) => sum + messageText(message).length, 0);
  return {
    raw,
    call: {
      span: chat,
      model: a['gen_ai.response.model'] ?? a['gen_ai.request.model'],
      messages: raw.map((message) => ({ role: message.role, segments: promptSegments(message.text, chunks) })),
      answers,
      promptCut: raw.length > 0 && fullChars > keptPrompt,
      answerCut: answers.length > 0 && fullAnswer > keptAnswer,
      promptWithheld: a['gen_ai.input.messages'] === WITHHELD,
      answerWithheld: a['gen_ai.output.messages'] === WITHHELD,
    },
  };
}

function buildSearch(span: SpanRow, refs: Map<string, number>, nextRef: () => number): RagSearch {
  const a = span.attributes;
  const contentAttribute = a['causeline.ai.documents.content'];
  const content = list(parse(contentAttribute));
  const chunks: RagChunk[] = list(parse(a['causeline.ai.documents'])).map((document, i) => {
    const text = optional(content[i]?.text);
    const normalized = text === undefined ? undefined : normalize(text);
    // The same text gets one number, however many searches returned it. Without text, the ID identifies it.
    const key = normalized || `id:${String(document.id)}`;
    let ref = refs.get(key);
    if (ref === undefined) {
      ref = nextRef();
      refs.set(key, ref);
    }
    return {
      ref,
      id: optional(document.id) ?? '',
      rank: typeof document.rank === 'number' ? document.rank : i + 1,
      score: typeof document.score === 'number' ? document.score : undefined,
      chars: typeof document.chars === 'number' ? document.chars : (text?.length ?? 0),
      keptChars: text?.length ?? 0,
      text: normalized,
      source: optional(content[i]?.source),
      placement: 'unknown',
    };
  });
  return {
    span,
    query: a['db.vector.query.content'] === WITHHELD ? undefined : a['db.vector.query.content'],
    filter: a['db.vector.query.filter'] === WITHHELD ? undefined : a['db.vector.query.filter'],
    topK: a['db.vector.query.top_k'],
    threshold: a['db.vector.query.similarity_threshold'],
    store: a['db.system'],
    collection: a['db.collection.name'],
    count: Number(a['causeline.ai.documents.count'] ?? chunks.length),
    chunks,
    textWithheld: contentAttribute === WITHHELD,
  };
}

/** The retrieval and model call a span belongs to, or nothing for spans that aren't part of one. */
export function ragView(spans: SpanRow[], span: SpanRow): RagView | undefined {
  if (span.kind !== 'AI_RETRIEVAL' && !isChat(span)) {
    return undefined;
  }
  const chats = spans.filter(isChat).sort((a, b) => a.offsetNanos - b.offsetNanos);
  const chat = isChat(span) ? span : callAfter(span, chats);
  const group = chat
    ? spans.filter((s) => s.kind === 'AI_RETRIEVAL' && callAfter(s, chats)?.spanId === chat.spanId)
    : [span];

  const refs = new Map<string, number>();
  let last = 0;
  const searches = [...group]
    .sort((a, b) => a.offsetNanos - b.offsetNanos)
    .map((s) => buildSearch(s, refs, () => ++last));
  const unique = new Map(
    searches.flatMap((s) => s.chunks).flatMap((c) => (c.text ? [[c.ref, c.text] as const] : [])),
  );
  const built = chat ? buildCall(chat, [...unique].map(([ref, text]) => ({ ref, text }))) : undefined;

  if (built && built.raw.length > 0) {
    for (const chunk of searches.flatMap((s) => s.chunks)) {
      if (chunk.text) {
        const found = built.raw.some((message) => message.text.includes(chunk.text as string));
        chunk.placement = found ? 'in-prompt' : built.call.promptCut ? 'unknown' : 'not-in-prompt';
      }
    }
  }
  return { searches, call: built?.call };
}

/** Every retrieval and model call in a trace, in order: one per chat call, then searches no call used. */
export function ragViews(spans: SpanRow[]): RagView[] {
  const views = spans
    .filter(isChat)
    .sort((a, b) => a.offsetNanos - b.offsetNanos)
    .flatMap((chat) => ragView(spans, chat) ?? []);
  const used = new Set(views.flatMap((view) => view.searches.map((search) => search.span.spanId)));
  const unused = spans.filter((span) => span.kind === 'AI_RETRIEVAL' && !used.has(span.spanId));
  return [...views, ...unused.flatMap((span) => ragView(spans, span) ?? [])];
}

/** The share of a bar a score fills: stores can return slightly more than 1. */
export const scoreShare = (score: number) => Math.min(1, Math.max(0, score));

export function embeddingInput(span: SpanRow): string | undefined {
  const value = span.attributes['causeline.ai.embedding.input'];
  if (value === undefined) {
    return undefined;
  }
  const texts = parse(value);
  return Array.isArray(texts) ? texts.map(String).join('\n\n') : value;
}
