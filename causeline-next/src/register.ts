// SPDX-License-Identifier: Apache-2.0
import { BatchSpanProcessor, NodeTracerProvider, type SpanProcessor } from '@opentelemetry/sdk-trace-node';
import { CauselineSpanExporter } from './exporter.js';
import { SpanMapper } from './mapper.js';
import { propagateFetch, propagationRule } from './propagation.js';
import type { CauselineNextOptions } from './types.js';

const DEFAULT_ENDPOINT = 'http://localhost:8080/causeline';

interface Resolved {
  endpoint: string;
  token: string;
  serviceName: string;
  propagateTo: (string | RegExp)[];
  redactKeys: string[];
}

/** Settings with defaults applied, or a reason Causeline stays off. */
export function resolveOptions(options: CauselineNextOptions = {}, env: NodeJS.ProcessEnv = process.env):
  { on: true; settings: Resolved } | { on: false; reason: string } {
  const enabled = options.enabled ?? env.NODE_ENV !== 'production';
  if (!enabled) {
    return { on: false, reason: 'off in production (NODE_ENV=production); pass enabled: true for a local production build' };
  }
  if (env.NEXT_RUNTIME === 'edge') {
    return { on: false, reason: 'the Edge runtime is not supported; call it only when NEXT_RUNTIME is "nodejs"' };
  }
  const token = options.token ?? env.CAUSELINE_TOKEN;
  if (!token) {
    return { on: false, reason: 'no access token: set CAUSELINE_TOKEN to your app\'s causeline.access-token' };
  }
  return {
    on: true,
    settings: {
      endpoint: options.endpoint ?? env.CAUSELINE_ENDPOINT ?? DEFAULT_ENDPOINT,
      token,
      serviceName: options.serviceName ?? env.CAUSELINE_SERVICE_NAME ?? 'next',
      propagateTo: options.propagateTo ?? [],
      redactKeys: options.redactKeys ?? [],
    },
  };
}

/**
 * A span processor that sends Next.js spans to Causeline, for apps that already set up
 * OpenTelemetry themselves (for example with `@vercel/otel`'s `registerOTel({ spanProcessors })`).
 * Returns undefined when Causeline is off. Pair it with {@link propagateFetch} if nothing else
 * adds trace headers to server-side fetch calls.
 */
export function createCauselineSpanProcessor(options: CauselineNextOptions = {}): SpanProcessor | undefined {
  const resolved = resolveOptions(options);
  if (!resolved.on) {
    return undefined;
  }
  return processorFor(resolved.settings, globalThis.fetch);
}

/**
 * Sets up Causeline for the Next.js server. Call it from `register()` in `instrumentation.ts`:
 *
 * ```ts
 * export async function register() {
 *   if (process.env.NEXT_RUNTIME === 'nodejs') {
 *     const { registerCauseline } = await import('@causeline/next');
 *     registerCauseline({ serviceName: 'storefront' });
 *   }
 * }
 * ```
 *
 * Next.js then records requests, route handlers, rendering and server-side fetch calls as
 * OpenTelemetry spans; this sends them to your Spring Boot app's Causeline and adds `traceparent`
 * to fetch calls to it, so one trace runs from the click, through Next.js, into Spring.
 * Does nothing in production.
 *
 * @returns a function that undoes the setup (useful in tests)
 */
export function registerCauseline(options: CauselineNextOptions = {}): () => Promise<void> {
  const resolved = resolveOptions(options);
  if (!resolved.on) {
    if (options.enabled !== false && process.env.NODE_ENV !== 'production') {
      console.warn(`[causeline] Next.js tracing is off: ${resolved.reason}`);
    }
    return async () => {};
  }
  const { settings } = resolved;
  // Taken before Next.js patches fetch, so uploads are never traced themselves.
  const platformFetch = globalThis.fetch;
  const provider = new NodeTracerProvider({ spanProcessors: [processorFor(settings, platformFetch)] });
  // Also installs the async context manager and the W3C trace-context propagator Next.js uses.
  provider.register();
  const restoreFetch = propagateFetch(propagationRule(settings.endpoint, settings.propagateTo));
  return async () => {
    restoreFetch();
    await provider.shutdown();
  };
}

function processorFor(settings: Resolved, fetchImpl: typeof fetch): SpanProcessor {
  const exporter = new CauselineSpanExporter(settings.endpoint, settings.token,
    new SpanMapper(settings.serviceName, settings.redactKeys), fetchImpl,
    propagationRule(settings.endpoint, settings.propagateTo));
  // Spans are sent in the background, a moment after they end; nothing waits on Causeline.
  return new BatchSpanProcessor(exporter, { scheduledDelayMillis: 500, maxQueueSize: 2048, maxExportBatchSize: 500 });
}
