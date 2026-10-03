// SPDX-License-Identifier: Apache-2.0
import { resetActions } from './actions.js';
import { expireAll } from './bodies.js';
import { watchClicks } from './clicks.js';
import { watchErrors } from './errors.js';
import { nowMs } from './clock.js';
import { patchFetch, patchXhr } from './network.js';
import { Recorder } from './recorder.js';

export interface InstallOptions {
  endpoint: string;
  /** Origins that receive `traceparent`. Defaults to the page's own origin. */
  propagateTo?: string[];
  /**
   * Remember clicks and form submits, and label requests that follow within a second, outside any
   * trace(), with the click as an inferred cause. Off by default: button labels can contain user data.
   */
  captureClicks?: boolean;
  /**
   * Requests to leave alone: no span, no traceparent. A string matches a same-origin path prefix
   * (`'/api/health'`) or a full URL prefix; a RegExp is tested against the full URL.
   */
  ignore?: Array<string | RegExp>;
  /** What to record. Everything is on by default; exports from the server still redact secrets. */
  capture?: CaptureOptions;
  flushIntervalMs?: number;
}

export interface CaptureOptions {
  /** Record full request URLs including query strings (default true); false keeps the path only. */
  query?: boolean;
  /** Record state values from useTracedState and Redux action payloads (default true). */
  stateValues?: boolean;
  /** Record request and response bodies of fetch and XHR calls, up to 16 KB each (default true). */
  bodies?: boolean;
  /** Record uncaught errors, unhandled promise rejections, and console.error calls made during an action (default true). */
  errors?: boolean;
}

const ALL: Required<CaptureOptions> = { query: true, stateValues: true, bodies: true, errors: true };
let captureOptions: Required<CaptureOptions> = ALL;

/** The active capture settings. */
export const getCapture = (): Required<CaptureOptions> => captureOptions;

const MAX_VALUE_CHARS = 2_000;

/** A value as it will be shown: JSON, truncated; undefined when state values are not captured. */
export function describeValue(value: unknown): string | undefined {
  if (!captureOptions.stateValues) {
    return undefined;
  }
  let text: string;
  try {
    text = typeof value === 'string' ? JSON.stringify(value) : (JSON.stringify(value) ?? String(value));
  } catch {
    text = String(value); // cyclic structures and the like
  }
  return text.length <= MAX_VALUE_CHARS ? text : `${text.slice(0, MAX_VALUE_CHARS)}…`;
}

function matcher(ignore: Array<string | RegExp> | undefined): (url: URL) => boolean {
  if (!ignore || ignore.length === 0) {
    return () => false;
  }
  return (url) =>
    ignore.some((rule) =>
      typeof rule === 'string'
        ? url.href.startsWith(rule) || (url.origin === location.origin && url.pathname.startsWith(rule))
        : rule.test(url.href),
    );
}

function definedOnly(capture: CaptureOptions | undefined): CaptureOptions {
  return Object.fromEntries(Object.entries(capture ?? {}).filter(([, v]) => v !== undefined));
}

export interface Installation {
  flush(): Promise<void>;
  uninstall(): void;
}

let recorder: Recorder | undefined;

/** The active recorder, or undefined when Causeline is not installed. */
export const getRecorder = (): Recorder | undefined => recorder;

/**
 * Starts capturing: patches fetch and XMLHttpRequest and begins uploading spans.
 * A second install while one is active is ignored.
 */
export function install(options: InstallOptions): Installation {
  if (recorder) {
    return { flush: () => Promise.resolve(), uninstall: () => {} };
  }
  const endpointUrl = new URL(options.endpoint, location.href);
  const originalFetch = globalThis.fetch;
  const active = new Recorder({
    endpoint: endpointUrl.href,
    send: (input, init) => originalFetch.call(globalThis, input, init),
    flushIntervalMs: options.flushIntervalMs,
  });
  const propagateTo = new Set(options.propagateTo ?? [location.origin]);
  const ignored = matcher(options.ignore);
  const network = {
    recorder: active,
    // The upload endpoint itself is always left alone, so uploads never trace themselves.
    isIngest: (url: URL) =>
      (url.origin === endpointUrl.origin && url.pathname === endpointUrl.pathname) || ignored(url),
    shouldPropagate: (url: URL) => propagateTo.has(url.origin),
  };
  captureOptions = { ...ALL, ...definedOnly(options.capture) };
  const restoreFetch = patchFetch(network);
  const restoreXhr = patchXhr(network);
  const stopClicks = options.captureClicks ? watchClicks(nowMs) : () => {};
  const stopErrors = captureOptions.errors ? watchErrors(active) : () => {};
  recorder = active;
  active.start();

  let uninstalled = false;
  return {
    // An explicit flush sends everything now, including spans still waiting for a body.
    flush: () => {
      expireAll();
      return active.flush();
    },
    uninstall: () => {
      if (uninstalled) {
        return;
      }
      uninstalled = true;
      expireAll();
      restoreFetch();
      restoreXhr();
      stopClicks();
      stopErrors();
      resetActions();
      captureOptions = ALL;
      active.stop();
      if (recorder === active) {
        recorder = undefined;
      }
    },
  };
}
