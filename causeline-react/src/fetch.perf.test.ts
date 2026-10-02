// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it } from 'vitest';
import { install } from './runtime.js';
import type { Installation } from './runtime.js';

/**
 * PRD section 11: main-thread time per captured fetch ≤ 0.2 ms p95. Skipped in `npm test`; run with
 * `npm run perf -w @causeline/react`. jsdom is slower than a browser, so passing here is conservative.
 */
const CALLS = 3_000;
const WARMUP = 500;

function respond(): Promise<Response> {
  return Promise.resolve(
    new Response('{"orderId":1,"status":"PAID"}', { status: 201, headers: { 'Content-Type': 'application/json' } }),
  );
}

async function timeCalls(): Promise<number[]> {
  const times: number[] = [];
  for (let i = 0; i < WARMUP + CALLS; i++) {
    const start = performance.now();
    const response = await fetch('/api/orders', { method: 'POST', body: '{"item":"book","quantity":1}' });
    await response.json(); // as an app would
    if (i >= WARMUP) {
      times.push(performance.now() - start);
    }
  }
  return times;
}

function p95(times: number[]): number {
  const sorted = [...times].sort((a, b) => a - b);
  return sorted[Math.ceil(0.95 * sorted.length) - 1] ?? 0;
}

let installation: Installation | undefined;

afterEach(() => installation?.uninstall());

describe.runIf((import.meta as { env?: { MODE?: string } }).env?.MODE === 'perf')('performance budget', () => {
  it('adds at most 0.2 ms of main-thread time per fetch at p95', async () => {
    globalThis.fetch = respond as typeof fetch;
    const baseline = await timeCalls();

    installation = install({ endpoint: '/causeline/api/spans', flushIntervalMs: 60_000 });
    const captured = await timeCalls();
    // The span is finished after the app has read the body: that deferred work is main-thread
    // time too, so it is measured as well.
    const deferredStart = performance.now();
    await installation.flush();
    const deferredPerCall = (performance.now() - deferredStart) / (WARMUP + CALLS);

    const added = p95(captured) - p95(baseline);
    console.log(
      `[perf] fetch p95 without SDK ${p95(baseline).toFixed(3)} ms, with SDK ${p95(captured).toFixed(3)} ms, ` +
        `added ${added.toFixed(3)} ms; deferred span work ${deferredPerCall.toFixed(3)} ms per call`,
    );
    expect(added).toBeLessThanOrEqual(0.2);
  }, 120_000);

  it('reports the share of body capture', async () => {
    globalThis.fetch = respond as typeof fetch;
    const baseline = await timeCalls();
    installation = install({ endpoint: '/causeline/api/spans', flushIntervalMs: 60_000, capture: { bodies: false } });
    const captured = await timeCalls();
    console.log(`[perf] without body capture: added ${(p95(captured) - p95(baseline)).toFixed(3)} ms at p95`);
  }, 120_000);
});
