// SPDX-License-Identifier: Apache-2.0
import { SpanKind, SpanStatusCode, type HrTime } from '@opentelemetry/api';
import type { ReadableSpan } from '@opentelemetry/sdk-trace-node';
import { describe, expect, it } from 'vitest';
import { SpanMapper } from './mapper.js';

const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';
const mapper = new SpanMapper('storefront', ['coupon']);

function span(partial: Partial<ReadableSpan> & { name: string }): ReadableSpan {
  const start: HrTime = [1_790_612_345, 123_000_000];
  return {
    kind: SpanKind.INTERNAL,
    spanContext: () => ({ traceId: TRACE, spanId: 'aaaaaaaaaaaaaaaa', traceFlags: 1 }),
    parentSpanContext: { traceId: TRACE, spanId: '00f067aa0ba902b7', traceFlags: 1 },
    startTime: start,
    endTime: [1_790_612_345, 323_000_000],
    duration: [0, 200_000_000],
    status: { code: SpanStatusCode.UNSET },
    attributes: {},
    links: [],
    events: [],
    ended: true,
    resource: {} as ReadableSpan['resource'],
    instrumentationScope: { name: 'next.js' },
    droppedAttributesCount: 0,
    droppedEventsCount: 0,
    droppedLinksCount: 0,
    ...partial,
  };
}

describe('SpanMapper', () => {
  it('maps the Next.js request span to a REQUEST named by method and route', () => {
    const [request] = mapper.map(span({
      name: 'POST /api/checkout',
      kind: SpanKind.SERVER,
      attributes: {
        'next.span_type': 'BaseServer.handleRequest',
        'http.method': 'POST',
        'http.route': '/api/checkout',
        'http.target': '/api/checkout?item=book&token=SECRET-TOKEN&coupon=SECRET-COUPON',
        'http.status_code': 201,
      },
    }));

    expect(request).toMatchObject({
      traceId: TRACE,
      spanId: 'aaaaaaaaaaaaaaaa',
      parentSpanId: '00f067aa0ba902b7',
      kind: 'REQUEST',
      name: 'POST /api/checkout',
      source: 'storefront',
      startTimeUnixNano: '1790612345123000000',
      durationNanos: 200_000_000,
      status: 'OK',
    });
    expect(request?.attributes).toMatchObject({
      'http.request.method': 'POST',
      'http.route': '/api/checkout',
      'http.response.status_code': '201',
      'url.path': '/api/checkout',
      // Sensitive values never leave the Next.js server; ordinary ones stay useful.
      'url.query': 'item=book&token=[REDACTED]&coupon=[REDACTED]',
    });
  });

  it('maps fetch calls to HTTP_CLIENT spans named by host and path, without query values', () => {
    const [call] = mapper.map(span({
      name: 'fetch POST http://localhost:8080/api/orders?session=SECRET',
      kind: SpanKind.CLIENT,
      attributes: {
        'next.span_type': 'AppRender.fetch',
        'http.method': 'POST',
        'http.url': 'http://localhost:8080/api/orders?session=SECRET',
        'http.status_code': 500,
      },
      status: { code: SpanStatusCode.ERROR },
    }));

    expect(call).toMatchObject({ kind: 'HTTP_CLIENT', name: 'POST localhost:8080/api/orders', status: 'ERROR' });
    expect(call?.attributes['url.full']).toBe('http://localhost:8080/api/orders?session=[REDACTED]');
    expect(JSON.stringify(call)).not.toContain('SECRET');
  });

  it('maps route handlers to CONTROLLER and rendering to RENDER', () => {
    const [handler] = mapper.map(span({
      name: 'executing api route (app) /api/checkout',
      attributes: { 'next.span_type': 'AppRouteRouteHandlers.runHandler', 'next.route': '/api/checkout' },
    }));
    const [render] = mapper.map(span({
      name: 'render route (app) /',
      attributes: { 'next.span_type': 'AppRender.getBodyResult', 'next.route': '/' },
    }));

    expect(handler).toMatchObject({ kind: 'CONTROLLER', name: 'Route handler /api/checkout' });
    expect(render).toMatchObject({ kind: 'RENDER', name: 'render route (app) /' });
  });

  it('turns recorded exceptions into EXCEPTION spans that point at the app code', () => {
    const spans = mapper.map(span({
      name: 'executing api route (app) /api/checkout',
      attributes: { 'next.span_type': 'AppRouteRouteHandlers.runHandler', 'next.route': '/api/checkout' },
      status: { code: SpanStatusCode.ERROR },
      events: [{
        name: 'exception',
        time: [1_790_612_345, 200_000_000],
        attributes: {
          'exception.type': 'TypeError',
          'exception.message': 'order is undefined',
          'exception.stacktrace': [
            'TypeError: order is undefined',
            '    at total (/app/node_modules/lib/index.js:1:1)',
            '    at POST (webpack-internal:///(rsc)/./app/api/checkout/route.ts:12:5)',
          ].join('\n'),
        },
      }],
    }));

    expect(spans).toHaveLength(2);
    const exception = spans[1];
    expect(exception).toMatchObject({
      kind: 'EXCEPTION',
      name: 'TypeError',
      parentSpanId: 'aaaaaaaaaaaaaaaa',
      durationNanos: 0,
      status: 'ERROR',
    });
    expect(exception?.spanId).toMatch(/^[0-9a-f]{16}$/);
    expect(exception?.attributes['code.location']).toBe('./app/api/checkout/route.ts:12:5');
    expect(exception?.attributes['exception.message']).toBe('order is undefined');
  });

  it('treats a span without a parent as a root', () => {
    const [root] = mapper.map(span({ name: 'GET /', kind: SpanKind.SERVER, parentSpanContext: undefined }));
    expect(root?.parentSpanId).toBeNull();
  });
});
