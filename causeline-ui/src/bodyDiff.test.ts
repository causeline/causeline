// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { SpanRow, TraceView } from './api';
import { jsonDiff, responseDiffs } from './bodyDiff';

const request = (name: string, body: string | undefined, source = 'checkout-demo'): SpanRow => ({
  spanId: name + source,
  parentSpanId: null,
  kind: 'REQUEST',
  name,
  source,
  status: 'OK',
  depth: 0,
  offsetNanos: 0,
  durationNanos: 1,
  selfNanos: 1,
  clockSkew: false,
  attributes: body === undefined ? {} : { 'http.response.body': body },
});

const trace = (...spans: SpanRow[]): TraceView => ({
  traceId: 't',
  name: 'Checkout',
  status: 'OK',
  startTimeUnixNano: 0,
  durationNanos: 1,
  spans,
  insights: [],
});

describe('JSON diff', () => {
  it('names changed, added and removed fields by their path', () => {
    const before = { orderId: 1, status: 'PAID', items: [{ sku: 'book', price: 1200 }], coupon: 'X' };
    const after = { orderId: 2, status: 'PAID', items: [{ sku: 'book', price: 1500 }, { sku: 'pen', price: 100 }], total: 1600 };

    expect(jsonDiff(before, after)).toEqual([
      { path: 'orderId', kind: 'changed', before: '1', after: '2' },
      { path: 'items[0].price', kind: 'changed', before: '1200', after: '1500' },
      { path: 'items[1]', kind: 'added', after: '{"sku":"pen","price":100}' },
      { path: 'coupon', kind: 'removed', before: '"X"' },
      { path: 'total', kind: 'added', after: '1600' },
    ]);
  });

  it('reports a value whose type changed once, and nothing for equal documents', () => {
    expect(jsonDiff({ price: 1200 }, { price: '12.00' })).toEqual([
      { path: 'price', kind: 'changed', before: '1200', after: '"12.00"' },
    ]);
    expect(jsonDiff({ a: { b: 1 } }, { a: null })).toEqual([{ path: 'a', kind: 'changed', before: '{"b":1}', after: 'null' }]);
    expect(jsonDiff({ a: [1, { b: 2 }] }, { a: [1, { b: 2 }] })).toEqual([]);
    expect(jsonDiff([1, 2], [1])).toEqual([{ path: '[1]', kind: 'removed', before: '2' }]);
  });
});

describe('response bodies of two runs', () => {
  it('pairs requests by name, preferring the server spans, and skips requests only one run made', () => {
    const before = trace(
      request('POST /api/orders', '{"status":"PAID"}', 'browser'),
      request('POST /api/orders', '{"status":"PAID"}'),
      request('GET /api/prices', '{"book":1200}'),
    );
    const after = trace(request('POST /api/orders', '{"status":"FAILED"}'), request('GET /api/stock', '{}'));

    expect(responseDiffs(before, after)).toEqual([
      { request: 'POST /api/orders', changes: [{ path: 'status', kind: 'changed', before: '"PAID"', after: '"FAILED"' }] },
    ]);
  });

  it('says so when bodies differ but are not JSON, and stays quiet when they are the same', () => {
    const html = responseDiffs(trace(request('GET /', '<p>a</p>')), trace(request('GET /', '<p>b</p>')));
    expect(html[0]?.changes).toEqual([]);
    expect(html[0]?.note).toContain('can’t be compared field by field');

    expect(responseDiffs(trace(request('GET /', '<p>a</p>')), trace(request('GET /', '<p>a</p>')))[0]?.note).toBeUndefined();
    expect(responseDiffs(trace(request('GET /', undefined)), trace(request('GET /', '{}')))).toEqual([]);
  });
});
