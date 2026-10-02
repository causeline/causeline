// SPDX-License-Identifier: Apache-2.0
import { currentAction } from './actions.js';
import { BODY_WAIT_MS, describeBody, isTextual, truncate, within } from './bodies.js';
import { recentClick } from './clicks.js';
import { nowMs, toDurationNanos, toUnixNanoString } from './clock.js';
import { newSpanId, newTraceId, traceparent } from './ids.js';
import type { Recorder } from './recorder.js';
import { getCapture } from './runtime.js';
import type { BrowserSpan } from './types.js';

export interface NetworkOptions {
  recorder: Recorder;
  isIngest(url: URL): boolean;
  shouldPropagate(url: URL): boolean;
}

interface Pending {
  traceId: string;
  spanId: string;
  parentSpanId: string | null;
  inferred: boolean;
  inferredCause: string | undefined;
  method: string;
  url: URL;
  start: number;
}

function begin(method: string, url: URL): Pending {
  const context = currentAction();
  const start = nowMs();
  return {
    traceId: context?.action.traceId ?? newTraceId(),
    spanId: newSpanId(),
    parentSpanId: context?.action.spanId ?? null,
    inferred: context?.inferred ?? false,
    // Only for requests no trace() claimed: a hint for the reader, never a link in the trace.
    inferredCause: context ? undefined : recentClick(start),
    method: method.toUpperCase(),
    url,
    start,
  };
}

interface Outcome {
  status: number | undefined;
  error?: unknown;
  /** When the response arrived; bodies may be read later without stretching the span. */
  endMs?: number;
  requestBody?: string;
  responseBody?: string;
}

function end(recorder: Recorder, pending: Pending, status: number | undefined, error?: unknown) {
  recorder.record(spanFor(pending, { status, error }));
}

function spanFor(pending: Pending, { status, error, endMs, requestBody, responseBody }: Outcome): BrowserSpan {
  const attributes: Record<string, string> = {
    'http.request.method': pending.method,
    'url.path': pending.url.pathname,
    'server.address': pending.url.host,
  };
  if (getCapture().query) {
    // Query strings can hold tokens; the server redacts them in exports, and capture.query=false drops them here.
    attributes['url.full'] = pending.url.href;
  }
  if (status !== undefined && status > 0) {
    attributes['http.response.status_code'] = String(status);
  }
  if (error !== undefined) {
    attributes['error.type'] = error instanceof Error ? error.name : 'NetworkError';
  }
  if (requestBody !== undefined) {
    attributes['http.request.body'] = requestBody;
  }
  if (responseBody !== undefined) {
    attributes['http.response.body'] = responseBody;
  }
  if (pending.inferred) {
    attributes['causeline.link'] = 'inferred';
  }
  if (pending.inferredCause) {
    attributes['causeline.inferred_cause'] = pending.inferredCause;
  }
  const failed = error !== undefined || status === undefined || status === 0 || status >= 400;
  return {
    traceId: pending.traceId,
    spanId: pending.spanId,
    parentSpanId: pending.parentSpanId,
    kind: 'REQUEST',
    name: `${pending.method} ${pending.url.pathname}`,
    startTimeUnixNano: toUnixNanoString(pending.start),
    durationNanos: toDurationNanos(pending.start, endMs ?? nowMs()),
    status: failed ? 'ERROR' : 'OK',
    attributes,
  };
}

/**
 * The response body as the application reads it. Rather than cloning the response and reading it
 * a second time (which costs as much as the rest of the capture and doubles memory for large
 * downloads), the response's own read methods are wrapped: when the app calls text(), json() or
 * arrayBuffer(), the text is recorded on the way through. Bodies read as a stream, or never read,
 * are not recorded.
 */
function tapResponseBody(response: Response): Promise<string | undefined> {
  if (response.body === null) {
    return Promise.resolve(undefined);
  }
  const type = response.headers.get('content-type');
  if (!isTextual(type)) {
    const length = response.headers.get('content-length');
    return Promise.resolve(`[${length ? `${length} bytes` : 'binary'}${type ? `, ${type}` : ''}]`);
  }
  return new Promise((resolve) => {
    const seen = (text: string) => resolve(text === '' ? undefined : truncate(text));
    const text = response.text.bind(response);
    const arrayBuffer = response.arrayBuffer.bind(response);
    const readText = () =>
      text().then(
        (value) => {
          seen(value);
          return value;
        },
        (error: unknown) => {
          resolve(undefined);
          throw error;
        },
      );
    const own = (value: unknown) => ({ value, configurable: true, writable: true });
    Object.defineProperties(response, {
      text: own(readText),
      // JSON.parse throws the same SyntaxError that Response.json() rejects with.
      json: own(() => readText().then((value) => JSON.parse(value) as unknown)),
      arrayBuffer: own(() =>
        arrayBuffer().then(
          (buffer) => {
            seen(new TextDecoder().decode(buffer));
            return buffer;
          },
          (error: unknown) => {
            resolve(undefined);
            throw error;
          },
        ),
      ),
    });
  });
}

/** The body of a Request object, read from a clone taken before the request is sent. */
function requestText(request: Request | undefined): Promise<string | undefined> | undefined {
  if (!request || request.body === null || !isTextual(request.headers.get('content-type'))) {
    return undefined;
  }
  return request
    .clone()
    .text()
    .then((text) => (text === '' ? undefined : truncate(text)));
}

/** Wraps `fetch`. Returns a function that restores the original. */
export function patchFetch(options: NetworkOptions): () => void {
  const original = globalThis.fetch;
  const patched: typeof fetch = async (input, init) => {
    const request = input instanceof Request ? input : undefined;
    const url = new URL(request ? request.url : String(input), location.href);
    if (options.isIngest(url)) {
      return original.call(globalThis, input, init);
    }
    const pending = begin(init?.method ?? request?.method ?? 'GET', url);
    const bodies = getCapture().bodies;
    const requestBody = !bodies
      ? undefined
      : init?.body !== undefined && init.body !== null
        ? Promise.resolve(describeBody(init.body))
        : requestText(request)?.catch(() => undefined);
    let nextInit = init;
    if (options.shouldPropagate(url)) {
      const headers = new Headers(init?.headers ?? request?.headers);
      headers.set('traceparent', traceparent(pending.traceId, pending.spanId));
      nextInit = { ...init, headers };
    }
    try {
      const response = await original.call(globalThis, input, nextInit);
      if (!bodies) {
        end(options.recorder, pending, response.status);
        return response;
      }
      const endMs = nowMs();
      // Recorded once the app has read the body; the application gets its response straight away.
      const responseBody = within(
        tapResponseBody(response),
        BODY_WAIT_MS,
        '[not recorded: the app did not read the body, or read it as a stream]',
      );
      options.recorder.recordLater(
        Promise.all([requestBody, responseBody]).then(([req, res]) =>
          spanFor(pending, { status: response.status, endMs, requestBody: req, responseBody: res }),
        ),
      );
      return response;
    } catch (error) {
      if (!requestBody) {
        end(options.recorder, pending, undefined, error);
      } else {
        const endMs = nowMs();
        options.recorder.recordLater(
          requestBody.then((req) => spanFor(pending, { status: undefined, error, endMs, requestBody: req })),
        );
      }
      throw error;
    }
  };
  globalThis.fetch = patched;
  return () => {
    if (globalThis.fetch === patched) {
      globalThis.fetch = original;
    }
  };
}

/** Wraps `XMLHttpRequest` (used by Axios and older clients). Returns a function that restores it. */
export function patchXhr(options: NetworkOptions): () => void {
  if (typeof XMLHttpRequest === 'undefined') {
    return () => {};
  }
  const proto = XMLHttpRequest.prototype;
  const originalOpen = proto.open;
  const originalSend = proto.send;
  const requests = new WeakMap<XMLHttpRequest, { method: string; url: URL }>();

  proto.open = function open(this: XMLHttpRequest, ...args: [string, string | URL, ...unknown[]]) {
    requests.set(this, { method: args[0], url: new URL(String(args[1]), location.href) });
    return Reflect.apply(originalOpen, this, args) as void;
  } as typeof proto.open;

  proto.send = function send(this: XMLHttpRequest, body?: Document | XMLHttpRequestBodyInit | null) {
    const request = requests.get(this);
    if (!request || options.isIngest(request.url)) {
      return originalSend.call(this, body);
    }
    const pending = begin(request.method, request.url);
    if (options.shouldPropagate(request.url)) {
      this.setRequestHeader('traceparent', traceparent(pending.traceId, pending.spanId));
    }
    const bodies = getCapture().bodies;
    const requestBody = bodies ? describeBody(body) : undefined;
    this.addEventListener(
      'loadend',
      () =>
        options.recorder.record(
          spanFor(pending, {
            status: this.status,
            requestBody,
            responseBody: bodies ? xhrResponseText(this) : undefined,
          }),
        ),
      { once: true },
    );
    return originalSend.call(this, body);
  };

  return () => {
    proto.open = originalOpen;
    proto.send = originalSend;
  };
}

function xhrResponseText(xhr: XMLHttpRequest): string | undefined {
  try {
    if (xhr.responseType === '' || xhr.responseType === 'text') {
      return xhr.responseText === '' ? undefined : truncate(xhr.responseText);
    }
    if (xhr.responseType === 'json') {
      return xhr.response === null ? undefined : truncate(JSON.stringify(xhr.response));
    }
    return describeBody(xhr.response);
  } catch {
    return undefined;
  }
}
