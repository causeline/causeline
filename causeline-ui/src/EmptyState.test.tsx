// SPDX-License-Identifier: Apache-2.0
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Onboarding, TraceSummary } from './api';
import { EmptyState, steps } from './EmptyState';
import { matches, slowdown } from './TraceList';

const onboarding = (partial: Partial<Onboarding>): Onboarding => ({
  appName: 'checkout-demo',
  serverSpans: 0,
  browserSpans: 0,
  namedActions: 0,
  lastServerSpanAt: null,
  lastBrowserSpanAt: null,
  ...partial,
});

describe('first-run checklist', () => {
  it('marks what has arrived and points at the next step', () => {
    const list = steps(onboarding({ serverSpans: 12 }));

    expect(list.map((s) => [s.id, s.done])).toEqual([
      ['backend', true],
      ['server', true],
      ['browser', false],
      ['actions', false],
    ]);
    const html = renderToString(<EmptyState onboarding={onboarding({ serverSpans: 12 })} />).replaceAll('<!-- -->', '');
    expect(html).toContain('Waiting for your');
    expect(html).toContain('Causeline is running in checkout-demo');
    expect(html).toContain('2 of 3 connected');
    // The open step shows how to fix it.
    expect(html).toContain('CauselineProvider endpoint=');
  });

  it('counts the optional step separately', () => {
    const html = renderToString(
      <EmptyState onboarding={onboarding({ serverSpans: 1, browserSpans: 4 })} />,
    ).replaceAll('<!-- -->', '');

    expect(html).toContain('3 of 3 connected');
    expect(html).toContain('All set: make a request');
    expect(html).toContain('optional');
  });
});

describe('trace filters', () => {
  const trace = (partial: Partial<TraceSummary>): TraceSummary => ({
    traceId: 'abc'.padEnd(32, '0'),
    name: 'Checkout',
    status: 'OK',
    startTimeUnixNano: 0,
    durationNanos: 300,
    spanCount: 3,
    replayOf: null,
    imported: false,
    ...partial,
  });

  it('search matches names and trace ID prefixes', () => {
    expect(matches(trace({}), 'check', 'all')).toBe(true);
    expect(matches(trace({}), 'abc', 'all')).toBe(true);
    expect(matches(trace({}), 'search', 'all')).toBe(false);
  });

  it('filters errors, replays and slower-than-usual runs', () => {
    expect(matches(trace({ status: 'ERROR' }), '', 'errors')).toBe(true);
    expect(matches(trace({}), '', 'errors')).toBe(false);
    expect(matches(trace({ replayOf: 'x' }), '', 'replays')).toBe(true);
    expect(slowdown(trace({ baselineNanos: 100 }))).toBe(3);
    expect(matches(trace({ baselineNanos: 100 }), '', 'slow')).toBe(true);
    expect(matches(trace({ baselineNanos: 250 }), '', 'slow')).toBe(false);
    expect(matches(trace({ baselineNanos: null }), '', 'slow')).toBe(false);
  });
});
