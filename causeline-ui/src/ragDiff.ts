// SPDX-License-Identifier: Apache-2.0
import type { TraceView } from './api';
import { ragViews, tokenUsage } from './rag';
import type { RagChunk, RagView } from './rag';

/** Smaller moves are noise: embeddings of the same text can differ in the last digits. */
const MIN_SCORE_CHANGE = 0.005;

export interface RagRunDiff {
  name: string;
  added: string[];
  removed: string[];
  moved: { id: string; from: number; to: number }[];
  scores: { id: string; before: number; after: number }[];
  /** Set only when the counts differ. */
  tokens?: { input: number; output: number };
  /** Shown side by side. Never part of {@link same}: two runs of a model rarely match word for word. */
  answers: { before?: string; after?: string };
  same: boolean;
}

/** Each document once, by ID, in the order the run saw them. Several searches can return the same one. */
function chunksOf(view: RagView | undefined): Map<string, { chunk: RagChunk; position: number }> {
  const chunks = new Map<string, { chunk: RagChunk; position: number }>();
  for (const chunk of view?.searches.flatMap((search) => search.chunks) ?? []) {
    if (chunk.id && !chunks.has(chunk.id)) {
      chunks.set(chunk.id, { chunk, position: chunks.size + 1 });
    }
  }
  return chunks;
}

const nameOf = (view: RagView) => view.call?.span.name ?? view.searches[0]?.span.name ?? 'AI call';

const answerOf = (view: RagView | undefined) => {
  const answers = view?.call?.answers ?? [];
  return answers.length > 0 ? answers.map((answer) => answer.text).join('\n\n') : undefined;
};

function tokenChange(before: RagView | undefined, after: RagView | undefined): RagRunDiff['tokens'] {
  const a = before?.call && tokenUsage(before.call.span);
  const b = after?.call && tokenUsage(after.call.span);
  if (!a || !b) {
    return undefined;
  }
  const input = (b.input ?? 0) - (a.input ?? 0);
  const output = (b.output ?? 0) - (a.output ?? 0);
  return input === 0 && output === 0 ? undefined : { input, output };
}

function compareRun(before: RagView | undefined, after: RagView | undefined): RagRunDiff {
  const left = chunksOf(before);
  const right = chunksOf(after);
  const shared = [...left].flatMap(([id, a]) => {
    const b = right.get(id);
    return b ? [{ id, a, b }] : [];
  });
  const moved = shared
    .filter(({ a, b }) => a.position !== b.position)
    .map(({ id, a, b }) => ({ id, from: a.position, to: b.position }));
  const scores = shared.flatMap(({ id, a, b }) => {
    const [from, to] = [a.chunk.score, b.chunk.score];
    return from !== undefined && to !== undefined && Math.abs(to - from) >= MIN_SCORE_CHANGE
      ? [{ id, before: from, after: to }]
      : [];
  });
  const added = [...right.keys()].filter((id) => !left.has(id));
  const removed = [...left.keys()].filter((id) => !right.has(id));
  const tokens = tokenChange(before, after);
  return {
    name: nameOf((before ?? after) as RagView),
    added,
    removed,
    moved,
    scores,
    tokens,
    answers: { before: answerOf(before), after: answerOf(after) },
    same: added.length + removed.length + moved.length + scores.length === 0 && !tokens,
  };
}

/** Compares the retrieval and model calls of two traces, call by call in order. */
export function ragDiffs(before: TraceView, after: TraceView): RagRunDiff[] {
  const left = ragViews(before.spans);
  const right = ragViews(after.spans);
  return Array.from({ length: Math.max(left.length, right.length) }, (_, i) => compareRun(left[i], right[i]));
}
