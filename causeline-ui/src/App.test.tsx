// SPDX-License-Identifier: Apache-2.0
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { SpanRow, TraceView } from './api';
import { App } from './App';
import { pausableSteps } from './ReplayPanel';
import { SpanDetail, Timeline } from './Timeline';
import { TraceList } from './TraceList';

describe('App', () => {
  it('shows the empty state before any trace exists', () => {
    const html = renderToString(<App />);

    expect(html).toContain('Causeline');
    expect(html).toContain('No traces yet');
  });
});

describe('TraceList', () => {
  it('labels replays with the trace they replay', () => {
    const original = {
      traceId: 'a'.repeat(32),
      name: 'Checkout',
      status: 'ERROR' as const,
      startTimeUnixNano: Date.UTC(2026, 8, 29, 8, 30, 0) * 1_000_000,
      durationNanos: 876_000_000,
      spanCount: 14,
      replayOf: null,
      imported: false,
    };
    const replayed = { ...original, traceId: 'b'.repeat(32), name: 'POST /api/orders', status: 'OK' as const, replayOf: original.traceId };

    const html = renderToString(<TraceList traces={[replayed, original]} selected={undefined} onSelect={() => {}} />).replaceAll(
      '<!-- -->',
      '',
    );

    expect(html).toContain('>Replay<');
    expect(html).toContain('of Checkout at ');
  });
});

describe('Timeline', () => {
  const trace: TraceView = {
    traceId: '0af7651916cd43dd8448eb211c80319c',
    name: 'Checkout',
    status: 'OK',
    startTimeUnixNano: 1_790_612_345_123_456_000,
    durationNanos: 876_000_000,
    spans: [
      {
        spanId: 'a000000000000001',
        parentSpanId: null,
        kind: 'UI_ACTION',
        name: 'Checkout',
        source: 'browser',
        status: 'OK',
        depth: 0,
        offsetNanos: 0,
        durationNanos: 876_000_000,
        selfNanos: 3_000_000,
        clockSkew: false,
        attributes: {},
      },
      {
        spanId: 'b000000000000001',
        parentSpanId: 'a000000000000001',
        kind: 'HTTP_CLIENT',
        name: 'POST localhost/fake-payment/charge',
        source: 'checkout-demo',
        status: 'OK',
        depth: 1,
        offsetNanos: 50_000_000,
        durationNanos: 810_000_000,
        selfNanos: 810_000_000,
        clockSkew: false,
        attributes: {},
      },
      {
        spanId: 'c000000000000001',
        parentSpanId: 'b000000000000001',
        kind: 'EXCEPTION',
        name: 'PaymentTimeoutException',
        source: 'checkout-demo',
        status: 'ERROR',
        depth: 2,
        offsetNanos: 860_000_000,
        durationNanos: 0,
        selfNanos: 0,
        clockSkew: false,
        attributes: {
          'exception.type': 'dev.causeline.examples.checkout.PaymentTimeoutException',
          'code.location': 'PaymentClient.java:38',
          'code.function': 'PaymentClient.charge',
        },
      },
    ],
    insights: [
      {
        rule: 'PRIMARY_BOTTLENECK',
        spanId: 'b000000000000001',
        label: 'Potential bottleneck: POST localhost/fake-payment/charge · 810 ms · 92%',
        sharePercent: 92,
      },
    ],
  };

  it('shows insights and marks the bottleneck row', () => {
    const html = renderToString(<Timeline trace={trace} />);

    expect(html).toContain('Potential bottleneck: POST localhost/fake-payment/charge');
    expect(html).toContain('>bottleneck<');
  });

  it('pins exceptions with their location and explains hidden details', () => {
    const html = renderToString(<Timeline trace={trace} />);

    expect(html).toContain('PaymentClient.java:38');
    expect(html).toContain('causeline.capture.exception-details=true');
  });

  it('shows handled exceptions apart from failures', () => {
    const handled: TraceView = {
      ...trace,
      status: 'OK',
      spans: [
        ...trace.spans.filter((s) => s.kind !== 'EXCEPTION'),
        {
          spanId: 'd000000000000001',
          parentSpanId: 'b000000000000001',
          kind: 'EXCEPTION',
          name: 'InventoryUnavailableException',
          source: 'checkout-demo',
          status: 'UNSET',
          depth: 2,
          offsetNanos: 100_000_000,
          durationNanos: 0,
          selfNanos: 0,
          clockSkew: false,
          attributes: {
            'causeline.exception.handled': 'true',
            'log.level': 'WARN',
            'log.logger': 'dev.causeline.examples.checkout.StockService',
            'code.location': 'StockService.java:41',
            'code.function': 'StockService.askInventory',
          },
        },
      ],
    };

    const html = renderToString(<Timeline trace={handled} />);

    expect(html).toContain('Handled exception: caught and logged, request continued');
    expect(html).toContain('>handled<');
    expect(html).toContain('logged at <!-- -->WARN');
    expect(html).not.toContain('>Exception</h3>');
  });

  it('lists spans with duration and share of self time', () => {
    const html = renderToString(<Timeline trace={trace} />);

    expect(html).toContain('POST localhost/fake-payment/charge');
    expect(html).toContain('810 ms');
    expect(html).toContain('92');
    expect(html).toContain('HTTP client');
  });
});

describe('SpanDetail', () => {
  it('shows the request body and SQL as readable blocks', () => {
    const html = renderToString(
      <SpanDetail
        span={{
          spanId: 'b000000000000009',
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
            'http.request.header.authorization': 'Bearer user-token',
            'http.request.body': '{"item":"book","quantity":1}',
            'db.query.text': 'select 1',
          },
        }}
      />,
    );

    expect(html).toContain('Bearer user-token');
    expect(html).toContain('Request body');
    expect(html).toContain('&quot;item&quot;: &quot;book&quot;');
    expect(html).toContain('select 1');
  });
});

describe('span details panel', () => {
  const span = {
    spanId: 'b'.repeat(16),
    parentSpanId: null,
    kind: 'SERVICE' as const,
    name: 'OrderService.createOrder',
    source: 'checkout-demo',
    status: 'OK' as const,
    depth: 2,
    offsetNanos: 12_000_000,
    durationNanos: 410_000_000,
    selfNanos: 12_000_000,
    clockSkew: false,
    attributes: { 'causeline.arguments': '{"item":"lamp","quantity":3}', 'causeline.return': '{"status":"PAID"}' },
  };

  it('offers stepping, a side-or-below choice and closing, and shows arguments and the result', () => {
    const html = renderToString(
      <SpanDetail
        span={span}
        placement="side"
        onPlacement={() => {}}
        onClose={() => {}}
        onStep={() => {}}
        position={{ index: 3, total: 12 }}
      />,
    ).replaceAll('<!-- -->', '');

    expect(html).toContain('4 / 12');
    expect(html).toContain('aria-label="On the side"');
    expect(html).toMatch(/aria-pressed="true"[^>]*aria-label="On the side"/);
    expect(html).toContain('aria-label="Close details (Esc)"');
    expect(html).toContain('Arguments');
    expect(html).toContain('&quot;quantity&quot;: 3');
    expect(html).toContain('Returned');
  });
});

describe('debugging tools', () => {
  const base = {
    parentSpanId: null,
    source: 'checkout-demo',
    status: 'OK' as const,
    depth: 0,
    offsetNanos: 0,
    durationNanos: 10_000_000,
    selfNanos: 1_000_000,
    clockSkew: false,
  };
  const request: SpanRow = {
    ...base,
    spanId: 'c'.repeat(16),
    kind: 'REQUEST',
    name: 'POST /api/orders',
    attributes: { 'http.request.method': 'POST', 'url.path': '/api/orders', 'http.request.body': '{"item":"book"}' },
  };
  const service: SpanRow = {
    ...base,
    spanId: 'd'.repeat(16),
    parentSpanId: request.spanId,
    depth: 1,
    kind: 'SERVICE',
    name: 'OrderService.createOrder',
    attributes: {
      'code.namespace': 'com.shop.OrderService',
      'code.function': 'createOrder',
      'causeline.arguments': '{"item":"lamp"}',
      'causeline.replay.edited': 'true',
    },
  };
  const cache: SpanRow = {
    ...base,
    spanId: 'e'.repeat(16),
    parentSpanId: service.spanId,
    depth: 2,
    kind: 'CACHE',
    name: 'GET prices',
    attributes: { 'cache.name': 'prices', 'cache.hit': 'false' },
  };
  const log: SpanRow = {
    ...base,
    spanId: 'f'.repeat(16),
    parentSpanId: service.spanId,
    depth: 2,
    kind: 'LOG',
    name: 'INFO OrderService: Order 7 paid',
    durationNanos: 0,
    attributes: { 'log.level': 'INFO', 'log.logger': 'com.shop.OrderService', 'log.message': 'Order 7 paid' },
  };
  const trace: TraceView = {
    traceId: '1af7651916cd43dd8448eb211c80319c',
    name: 'POST /api/orders',
    status: 'OK',
    startTimeUnixNano: 1_790_612_345_123_456_000,
    durationNanos: 10_000_000,
    spans: [request, service, cache, log],
    insights: [],
  };

  it('lists log lines in their own panel instead of as waterfall rows, and marks cache misses', () => {
    const html = renderToString(<Timeline trace={trace} />).replaceAll('<!-- -->', '');
    expect(html).toContain('aria-label="Logs"');
    expect(html).toContain('Order 7 paid');
    expect(html).toContain('3 spans');
    expect(html).toContain('1 log line');
    expect(html).not.toContain(`data-span-row="${log.spanId}"`);
    expect(html).toContain('>miss<');
    expect(html).toContain('>edited<');
  });

  it('offers opening the code and copying a request as cURL or a test', () => {
    const serviceHtml = renderToString(<SpanDetail span={service} logs={[log]} />).replaceAll('<!-- -->', '');
    expect(serviceHtml).toContain('Open in VS Code');
    expect(serviceHtml).toContain('Arguments (edited during replay)');
    expect(serviceHtml).toContain('Logged here (1)');

    const requestHtml = renderToString(<SpanDetail span={request} />);
    expect(requestHtml).toContain('Copy as cURL');
    expect(requestHtml).toContain('Copy as MockMvc test');
    expect(requestHtml).toContain('Copy as WebTestClient test');
  });

  it('offers only controller and service steps inside the replayed request as places to pause', () => {
    expect(pausableSteps([request, service, cache, log], request.spanId).map((s) => s.name)).toEqual(['OrderService.createOrder']);
    expect(pausableSteps([request, service], 'other')).toEqual([]);
  });
});
