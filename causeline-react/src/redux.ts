// SPDX-License-Identifier: Apache-2.0
import { currentAction } from './actions.js';
import { nowMs, toUnixNanoString } from './clock.js';
import { newSpanId } from './ids.js';
import { describeValue, getRecorder } from './runtime.js';

/** The parts of a Redux middleware signature Causeline uses; no dependency on redux itself. */
type Dispatch = (action: unknown) => unknown;

/**
 * Redux (and Redux Toolkit) middleware: actions dispatched during a traced action are recorded as
 * STATE_UPDATE spans named by `action.type`, with the payload unless `capture.stateValues` is false.
 *
 * ```ts
 * configureStore({ reducer, middleware: (m) => m().concat(causelineReduxMiddleware) });
 * ```
 */
export const causelineReduxMiddleware =
  () =>
  (next: Dispatch) =>
  (action: unknown): unknown => {
    const context = currentAction();
    const recorder = getRecorder();
    const type = (action as { type?: unknown } | null)?.type;
    if (context && recorder && typeof type === 'string') {
      recorder.record({
        traceId: context.action.traceId,
        spanId: newSpanId(),
        parentSpanId: context.action.spanId,
        kind: 'STATE_UPDATE',
        name: type,
        startTimeUnixNano: toUnixNanoString(nowMs()),
        durationNanos: 0,
        status: 'OK',
        attributes: withPayload(
          { 'causeline.state.key': type, 'causeline.state.source': 'redux' },
          (action as { payload?: unknown }).payload,
        ),
      });
    }
    return next(action);
  };

function withPayload(attributes: Record<string, string>, payload: unknown): Record<string, string> {
  const described = payload === undefined ? undefined : describeValue(payload);
  return described === undefined ? attributes : { ...attributes, 'causeline.state.value': described };
}
