// SPDX-License-Identifier: Apache-2.0
import type { ReactElement } from 'react';
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { LoadRun, Replayable, SpanRow } from './api';
import { LoadResult, confirmationText, flowSteps, formProblem, iterations, plannedRequests } from './LoadPanel';
import type { LoadForm } from './LoadPanel';

const text = (node: ReactElement) => renderToString(node).replaceAll('<!-- -->', '');
const MS = 1_000_000;

const span = (spanId: string, name: string, offsetMs: number, durationMs: number, source = 'checkout-demo'): SpanRow => ({
  spanId,
  parentSpanId: null,
  kind: 'REQUEST',
  name,
  source,
  status: 'OK',
  depth: 0,
  offsetNanos: offsetMs * MS,
  durationNanos: durationMs * MS,
  selfNanos: 0,
  clockSkew: false,
  attributes: {},
});

const replayable = (spanId: string, method: string, route: string): Replayable => ({
  spanId,
  method,
  route,
  unsafe: method !== 'GET',
  hasBody: false,
  bodyBytes: 0,
  bodyMissing: false,
  queryMissing: false,
});

const form = (change: Partial<LoadForm> = {}): LoadForm => ({
  mode: 'REQUEST',
  shape: 'CONCURRENCY',
  hits: 100,
  concurrency: 10,
  rate: 10,
  durationSeconds: 10,
  ramp: false,
  ...change,
});

const limits = { maxRequests: 1000, maxConcurrency: 20, maxDurationSeconds: 60, baselineHits: 5 };

describe('flowSteps', () => {
  it('orders the replayable server requests by start, with the pause after the previous response', () => {
    const spans = [
      span('b', 'GET /api/stock', 400, 10),
      span('x', 'GET /api/orders', 0, 5, 'browser'),
      span('a', 'POST /api/orders', 20, 80),
      span('c', 'GET /api/untracked', 450, 5),
      span('d', 'GET /api/later', 9_000, 5),
    ];
    const steps = flowSteps(spans, [
      replayable('a', 'POST', '/api/orders'),
      replayable('b', 'GET', '/api/stock'),
      replayable('d', 'GET', '/api/later'),
    ]);

    expect(steps.map((s) => s.replayable.route)).toEqual(['/api/orders', '/api/stock', '/api/later']);
    // 400 - (20 + 80); the last gap is capped at 5 s like the server does.
    expect(steps.map((s) => s.thinkMillis)).toEqual([0, 300, 5_000]);
  });
});

describe('the plan', () => {
  it('counts the baseline and every request of the flow', () => {
    expect(plannedRequests(form(), 1, limits)).toBe(105);
    expect(plannedRequests(form({ hits: 20 }), 3, limits)).toBe(75);
    expect(iterations(form({ shape: 'RATE', rate: 2.5, durationSeconds: 3 }))).toBe(8);
    // A ramp shares the hits between its levels: 1, 2, 4, 8, 10.
    expect(iterations(form({ ramp: true, hits: 52 }))).toBe(50);
  });

  it('says why a run would be refused before it is sent', () => {
    expect(formProblem(form(), 1, limits)).toBeUndefined();
    expect(formProblem(form({ hits: 996 }), 1, limits)).toContain('would send 1001 requests');
    expect(formProblem(form({ concurrency: 21 }), 1, limits)).toContain('between 1 and 20');
    expect(formProblem(form({ shape: 'RATE', durationSeconds: 61 }), 1, limits)).toContain('between 1 and 60 s');
  });

  it('names how often side effects repeat', () => {
    expect(confirmationText('POST', '/api/orders', 105, 'local')).toBe(
      'This will run POST /api/orders 105 times on local. Side effects such as orders and payments will repeat 105 times.',
    );
  });
});

describe('LoadResult', () => {
  const stats = (p95Ms: number) => ({ count: 40, p50: p95Ms * MS * 0.5, p95: p95Ms * MS, p99: p95Ms * MS, max: p95Ms * MS, perSecond: 30 });
  const run: LoadRun = {
    id: 'r1',
    state: 'DONE',
    stopReason: null,
    traceId: 'a'.repeat(32),
    spanId: 'b'.repeat(16),
    target: 'local',
    mode: 'REQUEST',
    shape: 'CONCURRENCY',
    hits: 40,
    concurrency: 20,
    rate: 0,
    durationSeconds: 0,
    ramp: false,
    analyzed: true,
    startedAt: '2026-10-23T10:00:00Z',
    elapsedMillis: 2_400,
    plannedRequests: 45,
    sent: 45,
    notSent: 0,
    tracesMissing: 0,
    tracesPending: 0,
    skipped: [],
    steps: [
      {
        spanId: 'b'.repeat(16),
        method: 'POST',
        route: '/api/orders',
        unsafe: true,
        thinkMillis: 0,
        baseline: stats(40),
        load: stats(640),
        ramp: [],
        baselineStatuses: { '201': 5 },
        loadStatuses: { '201': 38, '500': 2 },
        errors: {},
        timeline: [
          { second: 0, hits: 20, p95: 300 * MS },
          { second: 1, hits: 20, p95: 640 * MS },
        ],
        report: {
          baselineHits: 5,
          loadHits: 40,
          baselineDatabaseSpans: 2,
          loadDatabaseSpans: 2,
          rows: [
            { depth: 4, kind: 'SERVICE', name: 'PaymentClient.charge', baselineP50: 2 * MS, baselineP95: 3 * MS, loadP50: 300 * MS, loadP95: 580 * MS, baselineHits: 5, loadHits: 40, grew: true },
            { depth: 5, kind: 'DATABASE', name: 'INSERT orders', baselineP50: MS, baselineP95: MS, loadP50: MS, loadP95: 2 * MS, baselineHits: 5, loadHits: 40, grew: false },
            { depth: 5, kind: 'DATABASE', name: 'SELECT stock', baselineP50: null, baselineP95: null, loadP50: MS, loadP95: MS, baselineHits: 0, loadHits: 3, grew: false },
          ],
          failures: [
            { exceptionType: 'org.springframework.dao.CannotAcquireLockException', codeLocation: 'OrderService.java:44', handled: false, baselineHits: 0, loadHits: 2 },
          ],
        },
        kept: [
          { traceId: 'c'.repeat(32), step: 0, durationNanos: 700 * MS, httpStatus: 201, reason: 'SLOWEST' },
          { traceId: 'd'.repeat(32), step: 0, durationNanos: 20 * MS, httpStatus: 500, reason: 'FAILED' },
        ],
      },
    ],
  };

  it('puts the span that grew first, with its growth, and flags errors seen only under load', () => {
    const html = text(<LoadResult run={run} />);
    expect(html).toContain('PaymentClient.charge grew the most.');
    expect(html).toContain('+577 ms');
    expect(html).toContain('bg-error/10');
    expect(html).toContain('(in 0/5 + 3/40 hits)');
    expect(html).toContain('CannotAcquireLockException');
    expect(html).toContain('only under concurrency');
    expect(html).toContain('500: 2');
    expect(html).toContain('failed 500');
    expect(html).toContain('not for capacity planning');
  });

  it('says when nothing grew, and when the target has no span analysis', () => {
    const quiet: LoadRun = {
      ...run,
      steps: run.steps.map((s) => ({ ...s, report: s.report && { ...s.report, rows: s.report.rows.map((r) => ({ ...r, grew: false })) } })),
    };
    expect(text(<LoadResult run={quiet} />)).toContain('Nothing: no span’s self-time p95 grew');
    const remote: LoadRun = { ...run, analyzed: false, target: 'qa', steps: run.steps.map((s) => ({ ...s, report: null })) };
    expect(text(<LoadResult run={remote} />)).toContain('only a load replay to local has');
  });
});

describe('usage hints', () => {
  it('walk through a load test and how to read it', async () => {
    const { LOAD_HINTS, UsageHints } = await import('./UsageHints');
    const html = text(<UsageHints label="How to use a load test" hints={LOAD_HINTS} />);
    expect(html).toContain('How to use a load test');
    expect(html).toContain('Start load');
    expect(html).toContain('What grew');
    expect(html).toContain('Copy as k6');
  });
});
