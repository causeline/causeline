// SPDX-License-Identifier: Apache-2.0
import { Profiler } from 'react';
import type { ProfilerOnRenderCallback, ReactNode } from 'react';
import { renderAction } from './actions.js';
import { nowMs, toUnixNanoString } from './clock.js';
import { newSpanId } from './ids.js';
import { getRecorder } from './runtime.js';

export interface CauselineProfilerProps {
  /** Name shown on the RENDER span, e.g. "Checkout". */
  id: string;
  children?: ReactNode;
}

const onRender: ProfilerOnRenderCallback = (id, phase, actualDuration, _baseDuration, startTime) => {
  const recorder = getRecorder();
  const action = recorder ? renderAction(nowMs()) : undefined;
  // Renders outside a traced action (initial mount, unrelated updates) are not recorded.
  if (!recorder || !action) {
    return;
  }
  recorder.record({
    traceId: action.traceId,
    spanId: newSpanId(),
    parentSpanId: action.spanId,
    kind: 'RENDER',
    name: id,
    // Profiler times are relative to the page's time origin, like performance.now().
    startTimeUnixNano: toUnixNanoString(performance.timeOrigin + startTime),
    durationNanos: Math.max(0, Math.round(actualDuration * 1_000_000)),
    status: 'OK',
    attributes: { 'react.phase': phase },
  });
};

/**
 * Records a RENDER span each time the wrapped tree commits during a traced action, so the
 * timeline shows what React re-rendered because of it. Development only: React does not call
 * Profiler callbacks in production builds, and Causeline is off there anyway.
 *
 * ```tsx
 * <CauselineProfiler id="Checkout"><CheckoutPage /></CauselineProfiler>
 * ```
 */
export function CauselineProfiler({ id, children }: CauselineProfilerProps) {
  return (
    <Profiler id={id} onRender={onRender}>
      {children}
    </Profiler>
  );
}
