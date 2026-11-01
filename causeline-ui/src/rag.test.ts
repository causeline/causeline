// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { SpanKind, SpanRow } from './api';
import { callsModel, embeddingInput, promptSegments, ragView, scoreShare, tokenUsage } from './rag';

let nextId = 1;

function span(kind: SpanKind, offsetMs: number, durationMs: number, attributes: Record<string, string>): SpanRow {
  return {
    spanId: String(nextId++),
    parentSpanId: null,
    kind,
    name: kind,
    source: 'app',
    status: 'OK',
    depth: 0,
    offsetNanos: offsetMs * 1_000_000,
    durationNanos: durationMs * 1_000_000,
    selfNanos: 0,
    clockSkew: false,
    attributes,
  };
}

const documents = (...docs: { id: string; score?: number; chars?: number }[]) =>
  JSON.stringify(docs.map((d, i) => ({ rank: i + 1, chars: 10, ...d })));
const content = (...texts: string[]) => JSON.stringify(texts.map((text) => ({ text })));
const message = (text: string) => JSON.stringify([{ role: 'user', parts: [{ type: 'text', content: text }] }]);

const search = (offsetMs: number, docs: string, texts: string, extra: Record<string, string> = {}) =>
  span('AI_RETRIEVAL', offsetMs, 5, {
    'causeline.ai.documents': docs,
    'causeline.ai.documents.content': texts,
    ...extra,
  });
const chat = (offsetMs: number, prompt: string, extra: Record<string, string> = {}) =>
  span('AI_MODEL', offsetMs, 50, { 'gen_ai.operation.name': 'chat', 'gen_ai.input.messages': message(prompt), ...extra });

describe('ragView', () => {
  it('marks which chunks reached the prompt, across line endings', () => {
    const s = search(0, documents({ id: 'a', score: 0.9 }, { id: 'b', score: 0.4 }), content('First\nchunk', 'Dropped'));
    const c = chat(10, 'Question\r\n---\r\nFirst\r\nchunk\r\n---');

    const view = ragView([s, c], c);

    expect(view?.searches[0]?.chunks.map((x) => [x.id, x.ref, x.placement])).toEqual([
      ['a', 1, 'in-prompt'],
      ['b', 2, 'not-in-prompt'],
    ]);
    expect(view?.call?.messages[0]?.segments).toEqual([
      { text: 'Question\n---\n' },
      { text: 'First\nchunk', ref: 1 },
      { text: '\n---' },
    ]);
  });

  it('finds the same view from the search and from its chat call', () => {
    const s = search(0, documents({ id: 'a' }), content('One'));
    const c = chat(10, 'One');

    expect(ragView([s, c], s)?.call?.span.spanId).toBe(c.spanId);
    expect(ragView([s, c], c)?.searches[0]?.span.spanId).toBe(s.spanId);
  });

  it('groups parallel searches, and gives a chunk they share one number', () => {
    const first = search(0, documents({ id: 'a' }, { id: 'b' }), content('Shared', 'Only first'));
    const second = search(1, documents({ id: 'a' }, { id: 'c' }), content('Shared', 'Only second'));
    const c = chat(10, 'Shared Only first');

    const view = ragView([first, second, c], c);

    expect(view?.searches).toHaveLength(2);
    expect(view?.searches[1]?.chunks.map((x) => [x.ref, x.placement])).toEqual([
      [1, 'in-prompt'],
      [3, 'not-in-prompt'],
    ]);
  });

  it('keeps each chat call to the searches right before it', () => {
    const firstSearch = search(0, documents({ id: 'a' }), content('One'));
    const firstChat = chat(10, 'One');
    const secondSearch = search(100, documents({ id: 'b' }), content('Two'));
    const secondChat = chat(110, 'Two');
    const spans = [firstSearch, firstChat, secondSearch, secondChat];

    expect(ragView(spans, secondChat)?.searches.map((s) => s.span.spanId)).toEqual([secondSearch.spanId]);
    expect(ragView(spans, firstSearch)?.call?.span.spanId).toBe(firstChat.spanId);
  });

  it('claims nothing about placement when the text to compare is missing', () => {
    const noPrompt = span('AI_MODEL', 10, 50, { 'gen_ai.operation.name': 'chat' });
    const s = search(0, documents({ id: 'a' }), content('One'));
    expect(ragView([s, noPrompt], s)?.searches[0]?.chunks[0]?.placement).toBe('unknown');

    const noText = span('AI_RETRIEVAL', 0, 5, { 'causeline.ai.documents': documents({ id: 'a' }) });
    const c = chat(10, 'One');
    expect(ragView([noText, c], c)?.searches[0]?.chunks[0]?.placement).toBe('unknown');
  });

  it('does not call a chunk dropped when the prompt itself was cut', () => {
    const s = search(0, documents({ id: 'a' }), content('Far along'));
    const c = chat(10, 'Short', { 'causeline.ai.input.chars': '200000' });

    const view = ragView([s, c], c);

    expect(view?.call?.promptCut).toBe(true);
    expect(view?.searches[0]?.chunks[0]?.placement).toBe('unknown');
  });

  it('does not mistake line endings for a cut', () => {
    const s = search(0, documents({ id: 'a', chars: 7 }), content('One\r\nTwo'));
    const prompt = 'Say\r\nOne\r\nTwo';
    const c = chat(10, prompt, {
      'causeline.ai.input.chars': String(prompt.length),
      'gen_ai.output.messages': JSON.stringify([{ role: 'assistant', parts: [{ type: 'text', content: 'A\r\nB' }] }]),
      'causeline.ai.output.chars': '4',
    });

    const view = ragView([s, c], c);

    expect(view?.call).toMatchObject({ promptCut: false, answerCut: false });
    expect(view?.searches[0]?.chunks[0]).toMatchObject({ keptChars: 8, placement: 'in-prompt' });
  });

  it('reads an exported trace, whose text is withheld, as metadata only', () => {
    const withheld = '[withheld on export]';
    const s = search(0, documents({ id: 'a', score: 0.7 }), withheld, { 'db.vector.query.content': withheld });
    const c = span('AI_MODEL', 10, 50, {
      'gen_ai.operation.name': 'chat',
      'gen_ai.input.messages': withheld,
      'gen_ai.output.messages': withheld,
    });

    const view = ragView([s, c], c);

    expect(view?.searches[0]).toMatchObject({ textWithheld: true, query: undefined, chunks: [{ id: 'a', score: 0.7, placement: 'unknown' }] });
    expect(view?.call).toMatchObject({ promptWithheld: true, answerWithheld: true, messages: [] });
  });

  it('shows a chat call with no retrieval, and reads its answer', () => {
    const c = chat(0, 'Hello', {
      'gen_ai.output.messages': JSON.stringify([
        { role: 'assistant', parts: [{ type: 'text', content: 'Hi' }], finish_reason: 'LENGTH' },
      ]),
      'causeline.ai.output.chars': '500',
    });

    expect(ragView([c], c)).toMatchObject({
      searches: [],
      call: { answers: [{ text: 'Hi', finishReason: 'LENGTH' }], answerCut: true },
    });
  });

  it('puts system instructions before the conversation', () => {
    const c = chat(0, 'Hello', {
      'gen_ai.system_instructions': JSON.stringify([{ type: 'text', content: 'Be brief' }]),
    });

    expect(ragView([c], c)?.call?.messages.map((m) => m.role)).toEqual(['system', 'user']);
  });

  it('ignores other spans and survives bad values', () => {
    expect(ragView([], span('SERVICE', 0, 1, {}))).toBeUndefined();
    const embedding = span('AI_MODEL', 0, 1, { 'gen_ai.operation.name': 'embedding' });
    expect(ragView([embedding], embedding)).toBeUndefined();

    const broken = span('AI_RETRIEVAL', 0, 5, { 'causeline.ai.documents': 'not json' });
    expect(ragView([broken], broken)?.searches[0]?.chunks).toEqual([]);
  });
});

describe('promptSegments', () => {
  it('returns the text whole when nothing matches', () => {
    expect(promptSegments('Hello', [{ ref: 1, text: 'Other' }])).toEqual([{ text: 'Hello' }]);
    expect(promptSegments('', [])).toEqual([{ text: '' }]);
  });

  it('skips a chunk that overlaps an earlier one', () => {
    expect(
      promptSegments('abcdef', [
        { ref: 1, text: 'abcd' },
        { ref: 2, text: 'cdef' },
      ]),
    ).toEqual([{ text: 'abcd', ref: 1 }, { text: 'ef' }]);
  });
});

describe('small helpers', () => {
  it('reads token usage, or nothing without it', () => {
    const c = span('AI_MODEL', 0, 1, { 'gen_ai.usage.input_tokens': '120', 'gen_ai.usage.output_tokens': '12' });
    expect(tokenUsage(c)).toMatchObject({ input: 120, output: 12 });
    expect(tokenUsage(span('AI_MODEL', 0, 1, {}))).toBeUndefined();
  });

  it('knows when a trace calls a model', () => {
    expect(callsModel([span('AI_RETRIEVAL', 0, 1, {}), span('AI_MODEL', 1, 1, {})])).toBe(true);
    expect(callsModel([span('AI_RETRIEVAL', 0, 1, {}), span('REQUEST', 1, 1, {})])).toBe(false);
  });

  it('clamps scores to the bar', () => {
    expect(scoreShare(1.0000000000000002)).toBe(1);
    expect(scoreShare(-0.2)).toBe(0);
  });

  it('reads embedding input', () => {
    expect(embeddingInput(span('AI_MODEL', 0, 1, { 'causeline.ai.embedding.input': '["a","b"]' }))).toBe('a\n\nb');
    expect(embeddingInput(span('AI_MODEL', 0, 1, { 'causeline.ai.embedding.input': '[withheld on export]' }))).toBe(
      '[withheld on export]',
    );
    expect(embeddingInput(span('AI_MODEL', 0, 1, {}))).toBeUndefined();
  });
});
