// SPDX-License-Identifier: Apache-2.0
import { SpanKind, context, propagation, trace } from '@opentelemetry/api';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { propagationRule } from './propagation.js';
import { registerCauseline, resolveOptions } from './register.js';
import type { CauselineSpan } from './types.js';

const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';
const BROWSER_SPAN = '00f067aa0ba902b7';

describe('registerCauseline', () => {
  const realFetch = globalThis.fetch;
  let undo: (() => Promise<void>) | undefined;

  afterEach(async () => {
    await undo?.();
    undo = undefined;
    globalThis.fetch = realFetch;
    trace.disable();
    context.disable();
    propagation.disable();
  });

  it('continues the browser trace, adds traceparent to calls to Spring and uploads the spans', async () => {
    const calls: { url: string; headers: Headers; body?: string }[] = [];
    globalThis.fetch = vi.fn(async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
      calls.push({ url: String(input), headers: new Headers(init?.headers), body: init?.body as string | undefined });
      return new Response('{}', { status: String(input).includes('peer-spans') ? 202 : 201 });
    }) as typeof fetch;

    undo = registerCauseline({ token: 'dev', serviceName: 'storefront', endpoint: 'http://localhost:8080/causeline' });

    // What Next.js does for a request: continue the incoming trace, then call the backend inside it.
    const incoming = propagation.extract(context.active(), { traceparent: `00-${TRACE}-${BROWSER_SPAN}-01` });
    const tracer = trace.getTracer('next.js');
    await context.with(incoming, () =>
      tracer.startActiveSpan('POST /api/checkout', {
        kind: SpanKind.SERVER,
        attributes: { 'next.span_type': 'BaseServer.handleRequest', 'http.method': 'POST', 'http.route': '/api/checkout' },
      }, async (request) => {
        await fetch('http://localhost:8080/api/orders', { method: 'POST', headers: { 'X-App': 'yes' } });
        await fetch('https://payments.example.com/charge');
        request.end();
      }));
    // Next.js's own background fetches (outside any request, to third parties) are not sent.
    tracer.startSpan('fetch GET https://registry.npmjs.org/-/package/next/dist-tags', {
      kind: SpanKind.CLIENT,
      attributes: { 'http.url': 'https://registry.npmjs.org/-/package/next/dist-tags', 'http.method': 'GET' },
    }, context.active()).end();

    const toSpring = calls.find((c) => c.url === 'http://localhost:8080/api/orders');
    const traceparent = toSpring?.headers.get('traceparent');
    expect(traceparent).toMatch(new RegExp(`^00-${TRACE}-[0-9a-f]{16}-01$`));
    expect(toSpring?.headers.get('X-App')).toBe('yes');
    // Third parties don't get trace headers unless listed.
    expect(calls.find((c) => c.url.startsWith('https://payments'))?.headers.has('traceparent')).toBe(false);

    await undo(); // flushes
    undo = undefined;
    const upload = calls.find((c) => c.url === 'http://localhost:8080/causeline/api/peer-spans');
    expect(upload?.headers.get('X-Causeline-Token')).toBe('dev');
    const spans = JSON.parse(upload?.body ?? '[]') as CauselineSpan[];
    const request = spans.find((s) => s.kind === 'REQUEST');
    expect(request).toMatchObject({ traceId: TRACE, parentSpanId: BROWSER_SPAN, name: 'POST /api/checkout', source: 'storefront' });
    // Spring's request will hang under the span that was current when the call was made.
    expect(traceparent?.split('-')[2]).toBe(request?.spanId);
    expect(upload?.body).not.toContain('registry.npmjs.org');
  });

  it('stays off in production, on the Edge runtime and without a token', () => {
    expect(resolveOptions({ token: 'x' }, { NODE_ENV: 'production' }).on).toBe(false);
    expect(resolveOptions({ token: 'x', enabled: true }, { NODE_ENV: 'production' }).on).toBe(true);
    expect(resolveOptions({ token: 'x' }, { NODE_ENV: 'development', NEXT_RUNTIME: 'edge' }).on).toBe(false);
    expect(resolveOptions({}, { NODE_ENV: 'development' }).on).toBe(false);
    expect(resolveOptions({}, { NODE_ENV: 'development', CAUSELINE_TOKEN: 't' })).toMatchObject({
      on: true,
      settings: { endpoint: 'http://localhost:8080/causeline', serviceName: 'next', token: 't' },
    });
  });

  it('does nothing at all when off', async () => {
    const before = globalThis.fetch;
    undo = registerCauseline({ enabled: false });
    expect(globalThis.fetch).toBe(before);
  });
});

describe('propagationRule', () => {
  const rule = propagationRule('http://api.dev.internal:8080/causeline', ['https://orders.example.com/', /\.svc\.local\//]);

  it('allows localhost, the endpoint host and listed URLs only', () => {
    expect(rule(new URL('http://localhost:8080/api/orders'))).toBe(true);
    expect(rule(new URL('http://127.0.0.1:9000/x'))).toBe(true);
    expect(rule(new URL('http://api.dev.internal:8080/api/orders'))).toBe(true);
    expect(rule(new URL('https://orders.example.com/v1'))).toBe(true);
    expect(rule(new URL('http://billing.svc.local/charge'))).toBe(true);
    expect(rule(new URL('https://www.google.com/'))).toBe(false);
    expect(rule(new URL('https://orders.example.com.evil.io/'))).toBe(false);
  });
});
