// SPDX-License-Identifier: Apache-2.0
import type { ReactElement } from 'react';
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Comparison, ReplayOutcome, SpanRow } from './api';
import { ReplayResult, credentialsNote, draftFrom, parseHeaders, requestEdits } from './ReplayPanel';

/** Server-rendered HTML without React's `<!-- -->` text separators. */
const text = (node: ReactElement) => renderToString(node).replaceAll('<!-- -->', '');

const outcome: ReplayOutcome = {
  replayTraceId: 'b'.repeat(32),
  target: 'local',
  httpStatus: 201,
  durationNanos: 104_000_000,
  sentRedactedFields: true,
};

describe('credentialsNote', () => {
  const target = { name: 'qa', location: 'https://qa', auth: 'bearer', comparable: false, sendsOriginalCredentials: true };

  it('says the target profile replaces Authorization while other originals are resent', () => {
    expect(credentialsNote(target)).toContain("qa's bearer credentials replace the original Authorization");
  });

  it('warns that the user session goes along when there is no profile', () => {
    expect(credentialsNote({ ...target, auth: 'none' })).toContain("including the user's Authorization and cookies");
  });

  it('says originals are withheld when configured', () => {
    expect(credentialsNote({ ...target, sendsOriginalCredentials: false })).toContain('are not sent');
  });
});

describe('ReplayResult', () => {
  it('shows both runs side by side with what changed', () => {
    const comparison: Comparison = {
      instrumented: true,
      result: {
        original: { traceId: 'a'.repeat(32), status: 'ERROR', durationNanos: 876_000_000, httpStatus: '500' },
        replay: { traceId: 'b'.repeat(32), status: 'OK', durationNanos: 104_000_000, httpStatus: '201' },
        rows: [
          {
            depth: 0,
            kind: 'REQUEST',
            name: 'POST /api/orders',
            originalNanos: 876_000_000,
            replayNanos: 104_000_000,
            originalStatus: 'ERROR',
            replayStatus: 'OK',
            change: 'STATUS_CHANGED',
          },
          {
            depth: 1,
            kind: 'EXCEPTION',
            name: 'PaymentTimeoutException',
            originalNanos: 0,
            replayNanos: null,
            originalStatus: 'ERROR',
            replayStatus: null,
            change: 'ONLY_IN_ORIGINAL',
          },
        ],
      },
    };

    const html = text(<ReplayResult outcome={outcome} comparison={comparison} />);

    expect(html).toContain('Original (500)');
    expect(html).toContain('Replay (201)');
    expect(html).toContain('876 ms');
    expect(html).toContain('status changed');
    expect(html).toContain('only in original');
    expect(html).toContain('sent as [REDACTED]');
  });

  it('explains why there is no comparison for an uninstrumented target', () => {
    const html = text(
      <ReplayResult outcome={{ ...outcome, target: 'qa' }} comparison={{ instrumented: false, result: null }} />,
    );

    expect(html).toContain('HTTP 201');
    expect(html).toContain('causeline.replay.targets.qa.causeline-token');
  });
});

describe('editing a request before replaying it', () => {
  const span: SpanRow = {
    spanId: 'b000000000000001',
    parentSpanId: null,
    kind: 'REQUEST',
    name: 'POST /api/orders',
    source: 'checkout-demo',
    status: 'OK',
    depth: 0,
    offsetNanos: 0,
    durationNanos: 1,
    selfNanos: 1,
    clockSkew: false,
    attributes: {
      'url.path': '/api/orders',
      'url.query': 'coupon=SAVE10',
      'http.request.header.authorization': 'Bearer user-token',
      'http.request.header.content-type': 'application/json',
      'http.request.header.host': 'localhost:8080',
      'http.request.header.traceparent': '00-abc-def-01',
    },
  };

  it('starts from what was captured, without the headers every replay sets itself', () => {
    expect(draftFrom(span)).toEqual({
      path: '/api/orders',
      query: 'coupon=SAVE10',
      headers: 'authorization: Bearer user-token\ncontent-type: application/json',
    });
  });

  it('sends only what was changed', () => {
    const captured = draftFrom(span);

    expect(requestEdits(captured, captured)).toEqual({ edits: {}, changed: [] });
    expect(requestEdits(captured, { ...captured, query: '?access_token=abc' })).toEqual({
      edits: { query: 'access_token=abc' },
      changed: ['query'],
    });
  });

  it('a deleted header line leaves that header out', () => {
    const captured = draftFrom(span);
    const result = requestEdits(captured, { ...captured, headers: 'content-type: application/json' });

    expect(result).toEqual({ edits: { headers: { 'content-type': 'application/json' } }, changed: ['headers'] });
  });

  it('refuses lines that are not headers', () => {
    expect(parseHeaders('X-Ok: 1\n\nnot a header')).toEqual({ error: 'Line 3 is not "Name: value".' });
    expect(parseHeaders('Authorization: Bearer a:b')).toEqual({ headers: { Authorization: 'Bearer a:b' } });
  });

  it('leaves the path alone when only the route was captured', () => {
    const draft = draftFrom({ ...span, attributes: { 'http.route': '/api/orders/{id}' } });

    expect(draft.path).toBeUndefined();
    expect(requestEdits(draft, draft)).toEqual({ edits: {}, changed: [] });
  });
});
