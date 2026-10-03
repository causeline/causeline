// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
import { act, render, renderHook } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { trace } from './actions.js';
import { useTracedState } from './hooks.js';
import { causelineReduxMiddleware } from './redux.js';
import { CauselineProfiler } from './CauselineProfiler.js';
import { Recorder } from './recorder.js';
import { install } from './runtime.js';
import type { Installation } from './runtime.js';
import type { BrowserSpan } from './types.js';

const ENDPOINT = '/causeline/api/spans';
const TRACEPARENT = /^00-([0-9a-f]{32})-([0-9a-f]{16})-01$/;

let fetchMock: ReturnType<typeof vi.fn>;
let installation: Installation;

/** Spans uploaded to the ingest endpoint so far. */
async function uploaded(): Promise<BrowserSpan[]> {
  await installation.flush();
  return fetchMock.mock.calls
    .filter(([url]) => String(url).endsWith(ENDPOINT))
    .flatMap(([, init]) => JSON.parse(String((init as RequestInit).body)) as BrowserSpan[]);
}

function sentHeaders(call: number): Headers {
  return new Headers((fetchMock.mock.calls[call]?.[1] as RequestInit | undefined)?.headers);
}

beforeEach(() => {
  fetchMock = vi.fn(async () => new Response('{}', { status: 201 }));
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  installation = install({ endpoint: ENDPOINT, flushIntervalMs: 60_000 });
});

afterEach(() => {
  installation.uninstall();
});

describe('network capture', () => {
  it('adds traceparent to same-origin calls and records a REQUEST span', async () => {
    await fetch('/api/orders?coupon=SECRET', { method: 'POST' });

    const header = sentHeaders(0).get('traceparent');
    expect(header).toMatch(TRACEPARENT);
    const [, traceId, spanId] = TRACEPARENT.exec(header ?? '') ?? [];

    const [span] = await uploaded();
    expect(span).toMatchObject({
      traceId,
      spanId,
      parentSpanId: null,
      kind: 'REQUEST',
      name: 'POST /api/orders',
      status: 'OK',
    });
    expect(span?.attributes['http.response.status_code']).toBe('201');
    // Recorded by default; the server redacts it in exports, and capture.query=false drops it here.
    expect(span?.attributes['url.full']).toContain('coupon=SECRET');
  });

  it('never sends traceparent to other origins', async () => {
    await fetch('https://third-party.example/pixel');

    expect(sentHeaders(0).has('traceparent')).toBe(false);
    expect(await uploaded()).toHaveLength(1);
  });

  it('marks failed responses as errors', async () => {
    fetchMock.mockResolvedValueOnce(new Response('', { status: 500 }));

    await fetch('/api/orders');

    expect((await uploaded())[0]?.status).toBe('ERROR');
  });

  it('does not trace its own uploads', async () => {
    await fetch('/api/orders');
    await installation.flush();
    await installation.flush();

    expect(await uploaded()).toHaveLength(1);
  });

  it('leaves ignored requests alone', async () => {
    installation.uninstall();
    installation = install({ endpoint: ENDPOINT, flushIntervalMs: 60_000, ignore: ['/api/health', /analytics/] });

    await fetch('/api/health');
    await fetch('https://cdn.example/analytics.js');
    await fetch('/api/orders');

    expect(sentHeaders(0).has('traceparent')).toBe(false);
    expect((await uploaded()).map((s) => s.name)).toEqual(['GET /api/orders']);
  });

  it('keeps only the path when query capture is off', async () => {
    installation.uninstall();
    installation = install({ endpoint: ENDPOINT, flushIntervalMs: 60_000, capture: { query: false } });

    await fetch('/api/orders?coupon=SECRET');

    const [span] = await uploaded();
    expect(span?.attributes).not.toHaveProperty('url.full');
    expect(JSON.stringify(span)).not.toContain('SECRET');
  });

  it('restores the original fetch on uninstall', () => {
    installation.uninstall();

    expect(globalThis.fetch).toBe(fetchMock);
  });
});

describe('trace()', () => {
  it('links requests made during an action to the action', async () => {
    await trace('Checkout', async () => {
      await fetch('/api/orders', { method: 'POST' });
    });

    const spans = await uploaded();
    const action = spans.find((s) => s.kind === 'UI_ACTION');
    const request = spans.find((s) => s.kind === 'REQUEST');
    expect(action).toMatchObject({ name: 'Checkout', parentSpanId: null, status: 'OK' });
    expect(request?.traceId).toBe(action?.traceId);
    expect(request?.parentSpanId).toBe(action?.spanId);
    expect(sentHeaders(0).get('traceparent')).toContain(action?.traceId);
  });

  it('ctx.fetch links exactly to its own action while others overlap', async () => {
    let releaseFirst: () => void = () => {};
    const firstMayContinue = new Promise<void>((resolve) => (releaseFirst = resolve));

    const first = trace('Save draft', async (ctx) => {
      await firstMayContinue; // "Publish" starts meanwhile, so two actions are in flight
      await ctx.fetch('/api/drafts', { method: 'PUT' });
      await fetch('/api/autosave', { method: 'PUT' }); // plain fetch: best guess, marked inferred
    });
    const second = trace('Publish', async () => {
      releaseFirst();
      await new Promise((resolve) => setTimeout(resolve, 20));
    });
    await Promise.all([first, second]);

    const spans = await uploaded();
    const save = spans.find((s) => s.name === 'Save draft');
    const publish = spans.find((s) => s.name === 'Publish');
    const draft = spans.find((s) => s.name === 'PUT /api/drafts');
    const autosave = spans.find((s) => s.name === 'PUT /api/autosave');
    expect(draft).toMatchObject({ traceId: save?.traceId, parentSpanId: save?.spanId });
    expect(draft?.attributes).not.toHaveProperty('causeline.link');
    expect(autosave?.parentSpanId).toBe(publish?.spanId);
    expect(autosave?.attributes['causeline.link']).toBe('inferred');
  });

  it('marks the action as failed when it throws', async () => {
    await expect(
      trace('Checkout', async () => {
        throw new Error('boom');
      }),
    ).rejects.toThrow('boom');

    expect((await uploaded())[0]).toMatchObject({ kind: 'UI_ACTION', status: 'ERROR' });
  });

  it('starts a new trace for requests outside any action', async () => {
    await trace('First', async () => {
      await fetch('/api/a');
    });
    await fetch('/api/b');

    const requests = (await uploaded()).filter((s) => s.kind === 'REQUEST');
    expect(requests[0]?.traceId).not.toBe(requests[1]?.traceId);
    expect(requests[1]?.parentSpanId).toBeNull();
  });
});

describe('body capture', () => {
  it('records request and response bodies without holding back the response', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response('{"orderId":7,"status":"PAID"}', { status: 201, headers: { 'Content-Type': 'application/json' } }),
    );

    const response = await fetch('/api/orders', { method: 'POST', body: '{"item":"book","quantity":1}' });

    // The application still reads its own copy of the body.
    expect(await response.json()).toEqual({ orderId: 7, status: 'PAID' });
    const [span] = await uploaded();
    expect(span?.attributes['http.request.body']).toBe('{"item":"book","quantity":1}');
    expect(span?.attributes['http.response.body']).toBe('{"orderId":7,"status":"PAID"}');
  });

  it('reads the body of a Request object and describes binary bodies instead of reading them', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(new Uint8Array([1, 2, 3]), { headers: { 'Content-Type': 'image/png', 'Content-Length': '3' } }),
    );

    await fetch(new Request(new URL('/api/upload', location.href), { method: 'PUT', body: 'name=cover', headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }));

    const [span] = await uploaded();
    expect(span?.attributes['http.request.body']).toBe('name=cover');
    expect(span?.attributes['http.response.body']).toBe('[3 bytes, image/png]');
  });

  it('truncates large bodies', async () => {
    fetchMock.mockResolvedValueOnce(new Response('x'.repeat(20_000)));

    await (await fetch('/api/report')).text();

    const body = (await uploaded())[0]?.attributes['http.response.body'] ?? '';
    expect(body.length).toBeLessThan(17_000);
    expect(body).toContain('[truncated, 20000 chars in total]');
  });

  it('records the body however the app reads it, and says so when it does not', async () => {
    fetchMock.mockResolvedValueOnce(new Response('{"a":1}'));
    fetchMock.mockResolvedValueOnce(new Response('unread'));

    const buffer = await (await fetch('/api/one')).arrayBuffer();
    await fetch('/api/two');

    expect(new TextDecoder().decode(buffer)).toBe('{"a":1}');
    const spans = await uploaded();
    expect(spans.find((s) => s.name === 'GET /api/one')?.attributes['http.response.body']).toBe('{"a":1}');
    expect(spans.find((s) => s.name === 'GET /api/two')?.attributes['http.response.body']).toContain('not recorded');
  });

  it('keeps json() errors as the app would see them', async () => {
    fetchMock.mockResolvedValueOnce(new Response('not json'));

    const response = await fetch('/api/broken');

    await expect(response.json()).rejects.toBeInstanceOf(SyntaxError);
    expect((await uploaded())[0]?.attributes['http.response.body']).toBe('not json');
  });

  it('records no bodies when capture.bodies is false', async () => {
    installation.uninstall();
    installation = install({ endpoint: ENDPOINT, flushIntervalMs: 60_000, capture: { bodies: false } });

    await fetch('/api/orders', { method: 'POST', body: '{"password":"hunter2"}' });

    const [span] = await uploaded();
    expect(span?.attributes).not.toHaveProperty('http.request.body');
    expect(span?.attributes).not.toHaveProperty('http.response.body');
  });
});

describe('error capture', () => {
  // The page "handles" the synthetic errors, so the test runner doesn't report them as its own.
  const handled = (e: Event) => e.preventDefault();
  beforeEach(() => addEventListener('error', handled));
  afterEach(() => removeEventListener('error', handled));

  it('records an uncaught error under the action it happened in', async () => {
    let actionTrace: string | undefined;
    await trace('Checkout', async (ctx) => {
      actionTrace = ctx.traceId;
      const error = new TypeError('order is undefined');
      dispatchEvent(new ErrorEvent('error', { error, message: error.message, filename: 'http://localhost/src/Checkout.tsx', lineno: 12, colno: 5, cancelable: true }));
    });

    const spans = await uploaded();
    const action = spans.find((s) => s.kind === 'UI_ACTION');
    const exception = spans.find((s) => s.kind === 'EXCEPTION');
    expect(exception).toMatchObject({
      traceId: actionTrace,
      parentSpanId: action?.spanId,
      name: 'TypeError',
      status: 'ERROR',
    });
    expect(exception?.attributes).toMatchObject({
      'exception.message': 'order is undefined',
      'exception.escaped': 'uncaught',
      'code.location': 'http://localhost/src/Checkout.tsx:12:5',
    });
  });

  it('records unhandled rejections outside any action as a trace of their own', async () => {
    const event = new Event('unhandledrejection') as Event & { reason: unknown };
    event.reason = new Error('payment widget failed to load');
    dispatchEvent(event);

    const [exception] = (await uploaded()).filter((s) => s.kind === 'EXCEPTION');
    expect(exception).toMatchObject({ parentSpanId: null, name: 'Error' });
    expect(exception?.attributes['exception.escaped']).toBe('unhandled rejection');
  });

  it('records console.error during an action as a log line, and still logs it', async () => {
    installation.uninstall();
    const shown = vi.spyOn(console, 'error').mockImplementation(() => {});
    installation = install({ endpoint: ENDPOINT, flushIntervalMs: 60_000 });
    console.error('outside any action'); // not recorded
    await trace('Save', async () => {
      console.error('Could not save draft', { id: 7 });
    });

    const logs = (await uploaded()).filter((s) => s.kind === 'LOG');
    expect(logs).toHaveLength(1);
    expect(logs[0]?.attributes['log.message']).toBe('Could not save draft {"id":7}');
    expect(shown).toHaveBeenCalledWith('Could not save draft', { id: 7 });
    shown.mockRestore();
  });

  it('records no errors when capture.errors is false', async () => {
    installation.uninstall();
    installation = install({ endpoint: ENDPOINT, flushIntervalMs: 60_000, capture: { errors: false } });
    dispatchEvent(new ErrorEvent('error', { error: new Error('x'), message: 'x', cancelable: true }));
    expect((await uploaded()).filter((s) => s.kind === 'EXCEPTION')).toHaveLength(0);
  });
});

describe('XMLHttpRequest capture', () => {
  it('adds traceparent and records the request once it ends', async () => {
    const setHeader = vi.spyOn(XMLHttpRequest.prototype, 'setRequestHeader');
    const xhr = new XMLHttpRequest();
    const done = new Promise<void>((resolve) => xhr.addEventListener('loadend', () => resolve()));

    await trace('Legacy save', async () => {
      xhr.open('PUT', '/api/legacy?secret=SECRET');
      xhr.send('{}');
      await done; // jsdom has no server here, so the request fails; the span must still be recorded
    });

    expect(setHeader).toHaveBeenCalledWith('traceparent', expect.stringMatching(TRACEPARENT));
    const spans = await uploaded();
    const action = spans.find((s) => s.kind === 'UI_ACTION');
    const request = spans.find((s) => s.kind === 'REQUEST');
    expect(request).toMatchObject({ name: 'PUT /api/legacy', parentSpanId: action?.spanId, status: 'ERROR' });
    expect(request?.attributes['url.full']).toContain('secret=SECRET');
    expect(request?.attributes['http.request.body']).toBe('{}');
    setHeader.mockRestore();
  });
});

describe('click inference', () => {
  it('labels a request that follows a click, without linking it', async () => {
    installation.uninstall();
    installation = install({ endpoint: ENDPOINT, flushIntervalMs: 60_000, captureClicks: true });
    const button = document.createElement('button');
    button.textContent = 'Place order';
    document.body.append(button);

    button.click();
    await fetch('/api/orders', { method: 'POST' });

    const [request] = await uploaded();
    expect(request?.attributes['causeline.inferred_cause']).toBe('Click "Place order"');
    expect(request?.parentSpanId).toBeNull();
    button.remove();
  });

  it('is off by default', async () => {
    const button = document.createElement('button');
    button.textContent = 'Place order';
    document.body.append(button);

    button.click();
    await fetch('/api/orders');

    expect((await uploaded())[0]?.attributes).not.toHaveProperty('causeline.inferred_cause');
    button.remove();
  });
});

describe('causelineReduxMiddleware', () => {
  it('records action types and payloads dispatched during a traced action', async () => {
    const next = vi.fn((action: unknown) => action);
    const dispatch = causelineReduxMiddleware()(next);

    await trace('Checkout', async () => {
      dispatch({ type: 'cart/checkoutStarted', payload: { card: 'SECRET' } });
    });
    dispatch({ type: 'cart/ignored' });

    expect(next).toHaveBeenCalledTimes(2);
    const updates = (await uploaded()).filter((s) => s.kind === 'STATE_UPDATE');
    expect(updates.map((s) => s.name)).toEqual(['cart/checkoutStarted']);
    expect(updates[0]?.attributes['causeline.state.value']).toBe('{"card":"SECRET"}');
  });
});

describe('state value capture', () => {
  it('records nothing but the key when state values are off', async () => {
    installation.uninstall();
    installation = install({ endpoint: ENDPOINT, flushIntervalMs: 60_000, capture: { stateValues: false } });
    const { result } = renderHook(() => useTracedState('cardNumber', ''));
    const dispatch = causelineReduxMiddleware()((action: unknown) => action);

    await act(async () => {
      await trace('Pay', async () => {
        result.current[1]('4111 1111 1111 1111');
        dispatch({ type: 'payment/submitted', payload: { card: '4111' } });
      });
    });

    const updates = (await uploaded()).filter((s) => s.kind === 'STATE_UPDATE');
    expect(updates).toHaveLength(2);
    expect(JSON.stringify(updates)).not.toContain('4111');
  });

  it('describes functional updates by their result', async () => {
    const { result } = renderHook(() => useTracedState('count', 1));

    await act(async () => {
      await trace('Increment', async () => {
        result.current[1]((n) => n + 1);
      });
    });

    const [update] = (await uploaded()).filter((s) => s.kind === 'STATE_UPDATE');
    expect(update?.attributes['causeline.state.value']).toBe('2');
  });
});

describe('CauselineProfiler', () => {
  it('records renders caused by a traced action, not the initial mount', async () => {
    let setCount: (value: number) => void = () => {};
    function Cart() {
      const [count, set] = useState(0);
      setCount = set;
      return <span>{count} items</span>;
    }
    render(
      <CauselineProfiler id="Cart">
        <Cart />
      </CauselineProfiler>,
    );

    await act(async () => {
      await trace('Add to cart', async () => {
        setCount(1);
      });
    });

    const spans = await uploaded();
    const action = spans.find((s) => s.kind === 'UI_ACTION');
    const renders = spans.filter((s) => s.kind === 'RENDER');
    expect(renders).toHaveLength(1);
    expect(renders[0]).toMatchObject({ name: 'Cart', traceId: action?.traceId, parentSpanId: action?.spanId });
    expect(renders[0]?.attributes['react.phase']).toBe('update');
  });
});

describe('Recorder drop reporting', () => {
  it('tells the server how many spans it dropped, once', async () => {
    installation.uninstall();
    const send = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response('{}', { status: 202 }));
    const recorder = new Recorder({ endpoint: '/causeline/api/spans', send, maxBuffer: 2, maxBatch: 50 });
    for (let i = 0; i < 5; i++) {
      recorder.record(span(`a00000000000000${i}`));
    }

    await recorder.flush();
    recorder.record(span('b000000000000001'));
    await recorder.flush();

    const headers = send.mock.calls.map(([, init]) => new Headers((init as RequestInit).headers));
    expect(headers[0]?.get('X-Causeline-Dropped')).toBe('3');
    expect(headers[1]?.has('X-Causeline-Dropped')).toBe(false);
    installation = install({ endpoint: ENDPOINT, flushIntervalMs: 60_000 });
  });

  function span(spanId: string): BrowserSpan {
    return {
      traceId: 'a'.repeat(32),
      spanId,
      parentSpanId: null,
      kind: 'UI_ACTION',
      name: 'x',
      startTimeUnixNano: '1',
      durationNanos: 0,
      status: 'OK',
      attributes: {},
    };
  }
});

describe('useTracedState', () => {
  it('records updates made during an action, with their values', async () => {
    const { result } = renderHook(() => useTracedState('checkoutLoading', false));

    await act(async () => {
      await trace('Checkout', async () => {
        result.current[1](true);
      });
    });
    act(() => result.current[1](false)); // outside any action: not recorded

    expect(result.current[0]).toBe(false);
    const updates = (await uploaded()).filter((s) => s.kind === 'STATE_UPDATE');
    expect(updates).toHaveLength(1);
    expect(updates[0]).toMatchObject({ name: 'checkoutLoading', durationNanos: 0 });
    expect(updates[0]?.attributes['causeline.state.value']).toBe('true');
  });
});
