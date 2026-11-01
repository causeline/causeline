// SPDX-License-Identifier: Apache-2.0
import { renderToString } from 'react-dom/server';
import type { ReactElement } from 'react';
import { describe, expect, it } from 'vitest';
import type { SpanKind, SpanRow } from './api';
import { ragView } from './rag';
import { RagPanel, TokenBar } from './RagPanel';

const span = (kind: SpanKind, offsetNanos: number, attributes: Record<string, string>, status: SpanRow['status'] = 'OK'): SpanRow => ({
  spanId: `${kind}-${offsetNanos}`,
  parentSpanId: null,
  kind,
  name: kind,
  source: 'app',
  status,
  depth: 0,
  offsetNanos,
  durationNanos: 5,
  selfNanos: 0,
  clockSkew: false,
  attributes,
});

const retrieval = span('AI_RETRIEVAL', 0, {
  'db.vector.query.content': 'How long do refunds take?',
  'causeline.ai.documents': JSON.stringify([
    { id: 'refunds', rank: 1, chars: 16, score: 1.0000000000000002 },
    { id: 'shipping', rank: 2, chars: 13 },
  ]),
  'causeline.ai.documents.content': JSON.stringify([{ text: 'Refunds: 5 days.' }, { text: 'Ships in 2 d.' }]),
});
const chat = (attributes: Record<string, string>, status: SpanRow['status'] = 'OK') =>
  span('AI_MODEL', 10, { 'gen_ai.operation.name': 'chat', ...attributes }, status);
const prompt = JSON.stringify([{ role: 'user', parts: [{ type: 'text', content: 'Context: Refunds: 5 days.' }] }]);

const html = (node: ReactElement) => renderToString(node).replaceAll('<!-- -->', '');

function show(spans: SpanRow[], selected: SpanRow) {
  const view = ragView(spans, selected);
  if (!view) {
    throw new Error('no view');
  }
  return html(<RagPanel view={view} />);
}

describe('RagPanel', () => {
  it('shows the query, ranked chunks and where each one landed', () => {
    const model = chat({ 'gen_ai.input.messages': prompt });
    const page = show([retrieval, model], model);

    expect(page).toContain('How long do refunds take?');
    expect(page).toContain('>In prompt<');
    expect(page).toContain('>Not in prompt<');
    // A score just above 1 is the store's rounding, not something to show.
    expect(page).toContain('>1.000<');
    expect(page).toMatch(/<mark[^>]*><span[^>]*>1<\/span>Refunds: 5 days\.<\/mark>/);
  });

  it('shows a score only when the store gave one', () => {
    const model = chat({ 'gen_ai.input.messages': prompt });
    const page = show([retrieval, model], model);

    expect(page.match(/Similarity score from the vector store/g)).toHaveLength(1);
  });

  it('says so when a cancelled stream left no answer', () => {
    const model = chat({ 'gen_ai.input.messages': prompt });
    expect(show([retrieval, model], model)).toContain('No answer recorded');
  });

  it('explains a failed call', () => {
    const model = chat({ 'gen_ai.input.messages': prompt }, 'ERROR');
    expect(show([model], model)).toContain('The call failed before an answer came back.');
  });

  it('warns when the model hit its output limit', () => {
    const model = chat({
      'gen_ai.output.messages': JSON.stringify([
        { role: 'assistant', parts: [{ type: 'text', content: 'Partial' }], finish_reason: 'LENGTH' },
      ]),
    });
    expect(show([model], model)).toContain('Cut off by the output limit');
  });

  it('warns when a search returned nothing', () => {
    const empty = span('AI_RETRIEVAL', 0, { 'causeline.ai.documents': '[]', 'causeline.ai.documents.count': '0' });
    expect(show([empty], empty)).toContain('Nothing came back');
  });

  it('shows withheld text as withheld, with the metadata still there', () => {
    const withheld = '[withheld on export]';
    const search = span('AI_RETRIEVAL', 0, {
      'db.vector.query.content': withheld,
      'causeline.ai.documents': JSON.stringify([{ id: 'refunds', rank: 1, chars: 16, score: 0.8 }]),
      'causeline.ai.documents.content': withheld,
    });
    const model = chat({ 'gen_ai.input.messages': withheld, 'gen_ai.output.messages': withheld });
    const page = show([search, model], model);

    expect(page).toContain('refunds');
    expect(page).toContain('The prompt is withheld on export.');
    expect(page).toContain('The answer is withheld on export.');
    expect(page).not.toContain(withheld);
  });

  it('labels several searches', () => {
    const second = { ...retrieval, spanId: 'second', offsetNanos: 1 };
    const model = chat({ 'gen_ai.input.messages': prompt });
    const page = show([retrieval, second, model], model);

    expect(page).toContain('Search 1');
    expect(page).toContain('Search 2');
  });
});

describe('TokenBar', () => {
  it('shows input, cached, output and total, and never a cost', () => {
    const bar = html(
      <TokenBar
        span={chat({
          'gen_ai.usage.input_tokens': '1200',
          'gen_ai.usage.cache_read.input_tokens': '200',
          'gen_ai.usage.output_tokens': '40',
          'gen_ai.usage.total_tokens': '1240',
        })}
      />,
    );

    expect(bar).toMatch(/1,200<\/span> in/);
    expect(bar).toMatch(/200<\/span> cached/);
    expect(bar).toMatch(/40<\/span> out/);
    expect(bar).toMatch(/1,240<\/span> total/);
    expect(bar).not.toContain('$');
  });

  it('draws nothing without usage', () => {
    expect(html(<TokenBar span={chat({})} />)).toBe('');
  });
});
