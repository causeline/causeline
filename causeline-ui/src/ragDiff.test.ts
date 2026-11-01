// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { SpanKind, SpanRow, TraceView } from './api';
import { ragDiffs } from './ragDiff';

let nextId = 1;

function span(kind: SpanKind, offsetNanos: number, attributes: Record<string, string>): SpanRow {
  return {
    spanId: String(nextId++),
    parentSpanId: null,
    kind,
    name: kind === 'AI_MODEL' ? 'chat fake-chat' : 'simple query',
    source: 'app',
    status: 'OK',
    depth: 0,
    offsetNanos,
    durationNanos: 5,
    selfNanos: 0,
    clockSkew: false,
    attributes,
  };
}

const search = (offset: number, ...docs: [string, number?][]) =>
  span('AI_RETRIEVAL', offset, {
    'causeline.ai.documents': JSON.stringify(docs.map(([id, score], i) => ({ id, score, rank: i + 1, chars: 5 }))),
  });

const chat = (offset: number, answer: string, input = 100, output = 10) =>
  span('AI_MODEL', offset, {
    'gen_ai.operation.name': 'chat',
    'gen_ai.output.messages': JSON.stringify([{ role: 'assistant', parts: [{ type: 'text', content: answer }] }]),
    'gen_ai.usage.input_tokens': String(input),
    'gen_ai.usage.output_tokens': String(output),
  });

const trace = (...spans: SpanRow[]) => ({ spans }) as TraceView;

describe('ragDiffs', () => {
  it('lists chunks added, removed and moved by document ID', () => {
    const [run] = ragDiffs(
      trace(search(0, ['a', 0.9], ['b', 0.8], ['c', 0.7]), chat(10, 'x')),
      trace(search(0, ['c', 0.7], ['a', 0.9], ['d', 0.6]), chat(10, 'x')),
    );

    expect(run).toMatchObject({
      name: 'chat fake-chat',
      added: ['d'],
      removed: ['b'],
      moved: [
        { id: 'a', from: 1, to: 2 },
        { id: 'c', from: 3, to: 1 },
      ],
      same: false,
    });
  });

  it('lists score and token changes, ignoring tiny score moves', () => {
    const [run] = ragDiffs(
      trace(search(0, ['a', 0.9], ['b', 0.5000001]), chat(10, 'x', 100, 10)),
      trace(search(0, ['a', 0.4], ['b', 0.5]), chat(10, 'x', 130, 5)),
    );

    expect(run?.scores).toEqual([{ id: 'a', before: 0.9, after: 0.4 }]);
    expect(run?.tokens).toEqual({ input: 30, output: -5 });
  });

  it('shows answers side by side without counting them as a change', () => {
    const [run] = ragDiffs(
      trace(search(0, ['a', 0.9]), chat(10, 'Fourteen days.')),
      trace(search(0, ['a', 0.9]), chat(10, 'Within 14 days.')),
    );

    expect(run).toMatchObject({
      same: true,
      answers: { before: 'Fourteen days.', after: 'Within 14 days.' },
    });
  });

  it('counts a document once when several searches return it', () => {
    const [run] = ragDiffs(
      trace(search(0, ['a', 0.9]), search(1, ['a', 0.9], ['b', 0.8]), chat(10, 'x')),
      trace(search(0, ['a', 0.9], ['b', 0.8]), chat(10, 'x')),
    );

    expect(run?.same).toBe(true);
  });

  it('pairs calls in order, and shows a call only one run made', () => {
    const runs = ragDiffs(
      trace(search(0, ['a']), chat(10, 'one'), search(100, ['b']), chat(110, 'two')),
      trace(search(0, ['a']), chat(10, 'one')),
    );

    expect(runs).toHaveLength(2);
    expect(runs[0]?.same).toBe(true);
    expect(runs[1]).toMatchObject({ removed: ['b'], answers: { before: 'two' }, same: false });
  });

  it('includes searches no chat call used, and is empty without AI spans', () => {
    expect(ragDiffs(trace(search(0, ['a'])), trace(search(0, ['b'])))[0]).toMatchObject({
      name: 'simple query',
      added: ['b'],
      removed: ['a'],
    });
    expect(ragDiffs(trace(), trace())).toEqual([]);
  });

  it('compares an exported trace by its metadata', () => {
    const withheld = (s: SpanRow) => ({
      ...s,
      attributes: { ...s.attributes, 'gen_ai.output.messages': '[withheld on export]' },
    });
    const [run] = ragDiffs(
      trace(search(0, ['a', 0.9]), withheld(chat(10, 'x'))),
      trace(search(0, ['b', 0.9]), withheld(chat(10, 'x'))),
    );

    expect(run).toMatchObject({ added: ['b'], removed: ['a'], answers: {} });
    expect(run?.answers.before).toBeUndefined();
  });
});
