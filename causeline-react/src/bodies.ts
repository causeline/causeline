// SPDX-License-Identifier: Apache-2.0

/** Bodies are cut here so a batch of spans stays well inside the 256 KB ingest limit. */
export const MAX_BODY_CHARS = 16_384;

/** How long to wait for the app to read a response body before recording the span without it. */
export const BODY_WAIT_MS = 5_000;

const TEXTUAL = /^(text\/|application\/([\w.+-]*\+)?(json|xml|x-www-form-urlencoded|javascript|graphql)\b)/i;

/** Whether a body of this content type can be shown as text. Streams are never read. */
export function isTextual(contentType: string | null): boolean {
  if (contentType === null || contentType === '') {
    return true; // untyped bodies are usually text from hand-written handlers
  }
  return TEXTUAL.test(contentType) && !/event-stream/i.test(contentType);
}

export function truncate(text: string): string {
  return text.length <= MAX_BODY_CHARS
    ? text
    : `${text.slice(0, MAX_BODY_CHARS)}… [truncated, ${text.length} chars in total]`;
}

/**
 * A request body as it will be shown. Text-like bodies are shown whole (up to the limit); binary
 * and streamed bodies are described rather than read, so capture never consumes what the app sends.
 */
export function describeBody(body: unknown): string | undefined {
  if (body === null || body === undefined) {
    return undefined;
  }
  if (typeof body === 'string') {
    return body === '' ? undefined : truncate(body);
  }
  if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
    return truncate(body.toString());
  }
  if (typeof FormData !== 'undefined' && body instanceof FormData) {
    const parts: string[] = [];
    body.forEach((value, key) => {
      parts.push(
        typeof value === 'string' ? `${key}=${value}` : `${key}=[file ${value.name}, ${value.size} bytes]`,
      );
    });
    return truncate(parts.join('&'));
  }
  if (typeof Blob !== 'undefined' && body instanceof Blob) {
    return `[${body.size} bytes${body.type ? `, ${body.type}` : ''}]`;
  }
  if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) {
    return `[${body.byteLength} bytes]`;
  }
  if (typeof ReadableStream !== 'undefined' && body instanceof ReadableStream) {
    return '[stream]';
  }
  if (typeof Document !== 'undefined' && body instanceof Document) {
    return '[document]';
  }
  return truncate(String(body));
}

interface Deadline {
  at: number;
  expire(): void;
}

// One shared timer for all pending body reads: a timer per fetch cost more than the rest of
// the capture put together.
const deadlines = new Set<Deadline>();
let sweeper: ReturnType<typeof setInterval> | undefined;

function sweep(): void {
  const now = performance.now();
  for (const deadline of deadlines) {
    if (deadline.at <= now) {
      deadlines.delete(deadline);
      deadline.expire();
    }
  }
  if (deadlines.size === 0 && sweeper !== undefined) {
    clearInterval(sweeper);
    sweeper = undefined;
  }
}

/** Gives up on every pending body now, so an explicit flush sends what it has. */
export function expireAll(): void {
  for (const deadline of deadlines) {
    deadline.expire();
  }
  deadlines.clear();
  sweep(); // stops the shared timer
}

/** Resolves with `fallback` if `promise` takes longer than about `ms` (checked once a second). */
export function within<T>(promise: Promise<T>, ms: number, fallback: T): Promise<T> {
  return new Promise<T>((resolve) => {
    const deadline: Deadline = { at: performance.now() + ms, expire: () => resolve(fallback) };
    deadlines.add(deadline);
    sweeper ??= setInterval(sweep, 1_000);
    const settle = (value: T) => {
      deadlines.delete(deadline);
      resolve(value);
    };
    promise.then(settle, () => settle(fallback));
  });
}
