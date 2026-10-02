// SPDX-License-Identifier: Apache-2.0
import { nowMs, toDurationNanos, toUnixNanoString } from './clock.js';
import { newSpanId, newTraceId } from './ids.js';
import { getRecorder } from './runtime.js';
import type { SpanStatus } from './types.js';

interface Action {
  traceId: string;
  spanId: string;
}

/** Passed to the function given to {@link trace}, for exact linking when actions overlap. */
export interface ActionContext {
  /** The action's trace ID, or undefined when Causeline is not installed. */
  readonly traceId: string | undefined;
  /** `fetch` that always belongs to this action, even while other actions are in flight. */
  readonly fetch: typeof fetch;
}

// Browsers have no async context, so an action counts as "active" until its promise settles.
const active: Action[] = [];

// Set only for the synchronous start of a ctx.fetch call, which is when the patched fetch reads it.
let explicit: Action | undefined;

// React commits the renders caused by an action a moment after the action's promise settles.
const RENDER_GRACE_MS = 200;
let lastFinished: { action: Action; at: number } | undefined;

/** Forgets all actions; called when Causeline is uninstalled. */
export function resetActions(): void {
  active.length = 0;
  explicit = undefined;
  lastFinished = undefined;
}

/** The action a React render belongs to: one in flight, or one that finished just before. */
export function renderAction(now: number): Action | undefined {
  const inFlight = active.at(-1);
  if (inFlight) {
    return inFlight;
  }
  return lastFinished && now - lastFinished.at <= RENDER_GRACE_MS ? lastFinished.action : undefined;
}

/**
 * The action new work should belong to: the one named by ctx.fetch, otherwise the most recent
 * action in flight. With several in flight and no explicit choice, the link is marked as inferred.
 */
export function currentAction(): { action: Action; inferred: boolean } | undefined {
  if (explicit) {
    return { action: explicit, inferred: false };
  }
  const action = active.at(-1);
  return action ? { action, inferred: active.length > 1 } : undefined;
}

function contextFor(action: Action | undefined): ActionContext {
  return {
    traceId: action?.traceId,
    fetch: (input, init) => {
      const previous = explicit;
      explicit = action;
      try {
        // The patched fetch reads currentAction() before its first await, i.e. inside this call.
        return globalThis.fetch(input, init);
      } finally {
        explicit = previous;
      }
    },
  };
}

/**
 * Runs `fn` as a named user action. Requests and traced state updates made before its promise
 * settles become children of the action in one trace.
 *
 * ```ts
 * const onCheckout = () => trace('Checkout', async () => { await fetch('/api/orders', ...) });
 * // Several actions at once? Use ctx.fetch so each request links to exactly its own action:
 * const onSave = () => trace('Save draft', (ctx) => ctx.fetch('/api/drafts', { method: 'PUT' }));
 * ```
 */
export function trace<T>(name: string, fn: (ctx: ActionContext) => T): T {
  const recorder = getRecorder();
  if (!recorder) {
    return fn(contextFor(undefined));
  }
  const action: Action = { traceId: newTraceId(), spanId: newSpanId() };
  const start = nowMs();
  active.push(action);
  const ctx = contextFor(action);

  const finish = (status: SpanStatus) => {
    const index = active.indexOf(action);
    if (index >= 0) {
      active.splice(index, 1);
    }
    lastFinished = { action, at: nowMs() };
    recorder.record({
      traceId: action.traceId,
      spanId: action.spanId,
      parentSpanId: null,
      kind: 'UI_ACTION',
      name,
      startTimeUnixNano: toUnixNanoString(start),
      durationNanos: toDurationNanos(start, nowMs()),
      status,
      attributes: {},
    });
  };

  let result: T;
  try {
    result = fn(ctx);
  } catch (error) {
    finish('ERROR');
    throw error;
  }
  if (isPromiseLike(result)) {
    return result.then(
      (value) => {
        finish('OK');
        return value;
      },
      (error: unknown) => {
        finish('ERROR');
        throw error;
      },
    ) as T;
  }
  finish('OK');
  return result;
}

function isPromiseLike(value: unknown): value is PromiseLike<unknown> {
  return typeof (value as PromiseLike<unknown> | null)?.then === 'function';
}
