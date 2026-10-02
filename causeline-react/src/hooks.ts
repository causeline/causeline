// SPDX-License-Identifier: Apache-2.0
import { useCallback, useRef, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import { currentAction, trace } from './actions.js';
import { nowMs, toUnixNanoString } from './clock.js';
import { newSpanId } from './ids.js';
import { describeValue, getRecorder } from './runtime.js';

/**
 * Drop-in replacement for `useState`. Updates made during a traced action are recorded as
 * STATE_UPDATE spans in that action, with the new value (unless `capture.stateValues` is false).
 */
export function useTracedState<S>(key: string, initial: S | (() => S)): [S, Dispatch<SetStateAction<S>>] {
  const [value, setValue] = useState(initial);
  const current = useRef(value);
  current.current = value;
  const setTraced = useCallback<Dispatch<SetStateAction<S>>>(
    (next) => {
      const context = currentAction();
      const recorder = getRecorder();
      if (context && recorder) {
        const attributes: Record<string, string> = { 'causeline.state.key': key };
        // Updater functions must be pure (React may call them twice), so calling one here is safe.
        const nextValue = typeof next === 'function' ? (next as (previous: S) => S)(current.current) : next;
        const described = describeValue(nextValue);
        if (described !== undefined) {
          attributes['causeline.state.value'] = described;
        }
        recorder.record({
          traceId: context.action.traceId,
          spanId: newSpanId(),
          parentSpanId: context.action.spanId,
          kind: 'STATE_UPDATE',
          name: key,
          startTimeUnixNano: toUnixNanoString(nowMs()),
          durationNanos: 0,
          status: 'OK',
          attributes,
        });
      }
      setValue(next);
    },
    [key],
  );
  return [value, setTraced];
}

/** Wraps an event handler in {@link trace}, so each call is recorded as a named action. */
export function useTracedCallback<A extends unknown[], R>(name: string, handler: (...args: A) => R): (...args: A) => R {
  return useCallback((...args: A) => trace(name, () => handler(...args)), [name, handler]);
}
