// SPDX-License-Identifier: Apache-2.0
import { currentAction, renderAction } from './actions.js';
import { nowMs, toUnixNanoString } from './clock.js';
import { newSpanId, newTraceId } from './ids.js';
import type { Recorder } from './recorder.js';
import type { BrowserSpan } from './types.js';

const MAX_TEXT = 8_000;

/**
 * Puts errors in the page on the timeline:
 * - uncaught errors and unhandled promise rejections become EXCEPTION spans, under the action
 *   they happened in, or as a trace of their own;
 * - `console.error` calls during an action become LOG spans under it.
 *
 * @returns a function that stops watching
 */
export function watchErrors(recorder: Recorder): () => void {
  const onError = (event: ErrorEvent) => {
    const error = event.error as unknown;
    const where = event.filename ? `${event.filename}:${event.lineno}:${event.colno}` : undefined;
    recorder.record(exceptionSpan(error ?? event.message, 'uncaught', where));
  };
  const onRejection = (event: PromiseRejectionEvent) => {
    recorder.record(exceptionSpan(event.reason, 'unhandled rejection'));
  };
  const original = console.error;
  const patched = function causelineConsoleError(...args: unknown[]) {
    try {
      const owner = currentAction()?.action ?? renderAction(nowMs());
      if (owner) {
        recorder.record(logSpan(owner, args));
      }
    } catch {
      // Recording must never stop the app's own logging.
    }
    original.apply(console, args);
  };
  console.error = patched;
  addEventListener('error', onError);
  addEventListener('unhandledrejection', onRejection);
  return () => {
    removeEventListener('error', onError);
    removeEventListener('unhandledrejection', onRejection);
    // Only if nobody replaced it since; otherwise their wrapper keeps calling ours, which just passes through.
    if (console.error === patched) {
      console.error = original;
    }
  };
}

function exceptionSpan(reason: unknown, how: string, where?: string): BrowserSpan {
  const owner = currentAction()?.action ?? renderAction(nowMs());
  const error = reason instanceof Error ? reason : undefined;
  const type = error?.name ?? 'Error';
  const attributes: Record<string, string> = {
    'exception.type': type,
    'exception.message': truncate(error ? error.message : text(reason)),
    'exception.escaped': how,
  };
  if (error?.stack) {
    attributes['exception.stacktrace'] = truncate(error.stack);
  }
  const location = where ?? (error?.stack ? firstFrame(error.stack) : undefined);
  if (location) {
    attributes['code.location'] = location;
  }
  return {
    traceId: owner?.traceId ?? newTraceId(),
    spanId: newSpanId(),
    parentSpanId: owner?.spanId ?? null,
    kind: 'EXCEPTION',
    name: type,
    startTimeUnixNano: toUnixNanoString(nowMs()),
    durationNanos: 0,
    status: 'ERROR',
    attributes,
  };
}

function logSpan(owner: { traceId: string; spanId: string }, args: unknown[]): BrowserSpan {
  const message = truncate(args.map(text).join(' '));
  return {
    traceId: owner.traceId,
    spanId: newSpanId(),
    parentSpanId: owner.spanId,
    kind: 'LOG',
    name: `ERROR console: ${message.split('\n')[0]?.slice(0, 120) ?? ''}`,
    startTimeUnixNano: toUnixNanoString(nowMs()),
    durationNanos: 0,
    status: 'UNSET',
    attributes: { 'log.level': 'ERROR', 'log.logger': 'console', 'log.message': message },
  };
}

/** "at fn (http://localhost:5173/src/Checkout.tsx:12:5)" or "fn@http://…:12:5": the location of the first frame. */
function firstFrame(stack: string): string | undefined {
  for (const line of stack.split('\n').slice(1)) {
    const match = /\(?((?:https?|file):\/\/[^\s)]+:\d+:\d+)\)?\s*$/.exec(line.trim());
    if (match?.[1] && !match[1].includes('/node_modules/')) {
      return match[1];
    }
  }
  return undefined;
}

function text(value: unknown): string {
  if (value instanceof Error) {
    return `${value.name}: ${value.message}`;
  }
  if (typeof value === 'string') {
    return value;
  }
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    return String(value);
  }
}

function truncate(value: string): string {
  return value.length <= MAX_TEXT ? value : `${value.slice(0, MAX_TEXT)}…`;
}
