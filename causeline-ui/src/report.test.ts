// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { SpanRow, TraceView } from './api';
import { buildReport } from './report';

const span = (spanId: string, attributes: Record<string, string>, extra: Partial<SpanRow> = {}): SpanRow => ({
  spanId,
  parentSpanId: null,
  kind: 'REQUEST',
  name: 'POST /api/orders',
  source: 'checkout-demo',
  status: 'OK',
  depth: 0,
  offsetNanos: 0,
  durationNanos: 1_000_000,
  selfNanos: 1_000_000,
  clockSkew: false,
  attributes,
  ...extra,
});

const view: TraceView = {
  traceId: 'a'.repeat(32),
  name: 'Checkout <script>alert(1)</script>',
  status: 'ERROR',
  startTimeUnixNano: 0,
  durationNanos: 1_000_000,
  insights: [{ rule: 'PRIMARY_BOTTLENECK', spanId: 's1', label: 'Potential bottleneck: POST /charge', sharePercent: 75 }],
  spans: [
    span('s1', { 'http.request.header.authorization': 'Bearer live-secret', 'http.request.body': '{"password":"hunter2"}' }),
    span('s2', { 'db.query.text': "select * from users where email='alice@example.com'" }, { kind: 'DATABASE', depth: 1 }),
  ],
};

describe('bug report', () => {
  it('takes every value from the redacted export, never from the view', () => {
    const html = buildReport(view, {
      app: 'checkout-demo',
      spans: [
        { spanId: 's1', attributes: { 'http.request.header.authorization': '[REDACTED]', 'http.request.body': '{"password":"[REDACTED]"}' } },
      ],
    });

    expect(html).toContain('[REDACTED]');
    expect(html).not.toContain('live-secret');
    expect(html).not.toContain('hunter2');
    // s2 is not in the export: it gets no attributes rather than the unredacted ones.
    expect(html).not.toContain('alice@example.com');
    expect(html).toContain('Potential bottleneck: POST /charge');
  });

  it('is inert: names are escaped and there are no scripts', () => {
    const html = buildReport(view, { spans: [] });

    expect(html).toContain('Checkout &lt;script&gt;alert(1)&lt;/script&gt;');
    expect(html).not.toMatch(/<script/i);
    expect(html).toContain('Failed');
  });
});
