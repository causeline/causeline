// SPDX-License-Identifier: Apache-2.0
import { context, propagation } from '@opentelemetry/api';

const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]', '::1']);
const PATCHED = Symbol.for('causeline.next.fetch');

type FetchInput = Parameters<typeof globalThis.fetch>[0];

/** Which outgoing URLs get trace headers: localhost, the endpoint's host, and anything listed. */
export function propagationRule(endpoint: string, propagateTo: (string | RegExp)[] = []): (url: URL) => boolean {
  let endpointHost: string | undefined;
  try {
    endpointHost = new URL(endpoint).host;
  } catch {
    endpointHost = undefined;
  }
  return (url) =>
    (url.protocol === 'http:' || url.protocol === 'https:')
    && (LOCAL_HOSTS.has(url.hostname) || url.host === endpointHost
      || propagateTo.some((rule) => (typeof rule === 'string' ? url.href.startsWith(rule) : rule.test(url.href))));
}

/**
 * Wraps the global `fetch` so calls from route handlers, server actions and server components
 * carry `traceparent` for the span that is current, letting the Spring Boot app continue the
 * trace. Next.js records fetch spans but does not send trace headers itself. Headers the
 * application set are never replaced.
 *
 * @returns a function that restores the previous fetch
 */
export function propagateFetch(shouldPropagate: (url: URL) => boolean): () => void {
  const previous = globalThis.fetch;
  if ((previous as { [PATCHED]?: boolean })[PATCHED]) {
    return () => {};
  }
  const patched = function causelineFetch(input: FetchInput, init?: RequestInit): Promise<Response> {
    const url = targetOf(input);
    if (!url || !shouldPropagate(url)) {
      return previous(input, init);
    }
    const headers = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined));
    if (headers.has('traceparent')) {
      return previous(input, init);
    }
    const carrier: Record<string, string> = {};
    propagation.inject(context.active(), carrier);
    if (!carrier.traceparent) {
      return previous(input, init);
    }
    for (const [name, value] of Object.entries(carrier)) {
      headers.set(name, value);
    }
    return previous(input, { ...init, headers });
  } as typeof fetch;
  (patched as { [PATCHED]?: boolean })[PATCHED] = true;
  globalThis.fetch = patched;
  return () => {
    if (globalThis.fetch === patched) {
      globalThis.fetch = previous;
    }
  };
}

function targetOf(input: FetchInput): URL | undefined {
  try {
    return new URL(input instanceof Request ? input.url : String(input));
  } catch {
    return undefined; // relative URLs have no meaning on the server
  }
}
