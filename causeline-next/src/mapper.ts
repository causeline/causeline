// SPDX-License-Identifier: Apache-2.0
import { SpanKind, SpanStatusCode, type HrTime } from '@opentelemetry/api';
import type { ReadableSpan } from '@opentelemetry/sdk-trace-node';
import type { CauselineSpan, CauselineSpanKind } from './types.js';

const DEFAULT_SENSITIVE = 'token|secret|password|session|auth';
const MAX_STACK_CHARS = 8_000;

/** Turns the spans Next.js records into Causeline spans, hiding sensitive query values on the way out. */
export class SpanMapper {
  private readonly sensitive: RegExp;

  constructor(
    private readonly source: string,
    redactKeys: string[] = [],
  ) {
    const extra = redactKeys.map((k) => k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    this.sensitive = new RegExp([DEFAULT_SENSITIVE, ...extra].join('|'), 'i');
  }

  /** The span itself, plus a zero-length EXCEPTION span for each exception recorded on it. */
  map(span: ReadableSpan): CauselineSpan[] {
    const context = span.spanContext();
    const raw = span.attributes;
    const text = (key: string): string | undefined => {
      const value = raw[key];
      return value === undefined || value === null ? undefined : String(value);
    };
    const kind = kindOf(span, text('next.span_type'));
    const attributes: Record<string, string> = {};
    const put = (key: string, value: string | undefined) => {
      if (value !== undefined && value !== '') {
        attributes[key] = value;
      }
    };
    let name = span.name;
    switch (kind) {
      case 'REQUEST': {
        const method = text('http.method');
        const route = text('http.route') ?? text('next.route');
        put('http.request.method', method);
        put('http.route', route);
        put('http.response.status_code', text('http.status_code'));
        const target = text('http.target');
        if (target) {
          const [path, query] = splitQuery(target);
          put('url.path', path);
          put('url.query', query === undefined ? undefined : this.redactQuery(query));
        }
        if (text('next.rsc') === 'true') {
          put('next.rsc', 'true');
        }
        name = method && route ? `${method} ${route}` : span.name;
        break;
      }
      case 'HTTP_CLIENT': {
        const method = text('http.method') ?? 'GET';
        const full = text('http.url');
        put('http.request.method', method);
        put('http.response.status_code', text('http.status_code'));
        if (full) {
          const url = parse(full);
          put('server.address', url ? url.host : undefined);
          put('url.full', this.redactUrl(full));
          name = url ? `${method} ${url.host}${url.pathname}` : `${method} ${full}`;
        }
        break;
      }
      case 'CONTROLLER': {
        const route = text('next.route');
        put('http.route', route);
        name = route ? `Route handler ${route}` : span.name;
        break;
      }
      default:
        put('next.route', text('next.route'));
    }
    put('next.span_type', text('next.span_type'));

    const status = span.status.code === SpanStatusCode.ERROR ? 'ERROR' : 'OK';
    const parent = span.parentSpanContext?.spanId;
    const own: CauselineSpan = {
      traceId: context.traceId,
      spanId: context.spanId,
      parentSpanId: parent && parent !== '0000000000000000' ? parent : null,
      kind,
      name,
      source: this.source,
      startTimeUnixNano: nanos(span.startTime).toString(),
      durationNanos: Math.max(0, Number(nanos(span.duration))),
      status,
      attributes,
    };
    return [own, ...this.exceptions(span, own)];
  }

  private exceptions(span: ReadableSpan, owner: CauselineSpan): CauselineSpan[] {
    return span.events
      .filter((event) => event.name === 'exception')
      .map((event) => {
        const values = event.attributes ?? {};
        const type = String(values['exception.type'] ?? 'Error');
        const attributes: Record<string, string> = { 'exception.type': type };
        if (values['exception.message'] !== undefined) {
          attributes['exception.message'] = String(values['exception.message']);
        }
        const stack = values['exception.stacktrace'];
        if (stack !== undefined) {
          const trace = String(stack);
          attributes['exception.stacktrace'] = trace.length > MAX_STACK_CHARS ? `${trace.slice(0, MAX_STACK_CHARS)}…` : trace;
          const frame = appFrame(trace);
          if (frame) {
            attributes['code.location'] = frame;
          }
        }
        return {
          traceId: owner.traceId,
          spanId: randomSpanId(),
          parentSpanId: owner.spanId,
          kind: 'EXCEPTION' as const,
          name: type,
          source: owner.source,
          startTimeUnixNano: nanos(event.time).toString(),
          durationNanos: 0,
          status: 'ERROR' as const,
          attributes,
        };
      });
  }

  /** Values of sensitive query parameters become [REDACTED]. */
  redactQuery(query: string): string {
    return query
      .split('&')
      .filter((pair) => pair !== '')
      .map((pair) => {
        const eq = pair.indexOf('=');
        if (eq < 0) {
          return pair;
        }
        let key = pair.slice(0, eq);
        try {
          key = decodeURIComponent(key);
        } catch {
          // Keep the raw key.
        }
        return this.sensitive.test(key) ? `${pair.slice(0, eq)}=[REDACTED]` : pair;
      })
      .join('&');
  }

  private redactUrl(full: string): string {
    const [base, query] = splitQuery(full);
    return query === undefined ? base : `${base}?${this.redactQuery(query)}`;
  }
}

/**
 * Next.js names what each span is in `next.span_type`; see next/dist/server/lib/trace/constants.
 * Unknown types fall back to the OpenTelemetry span kind.
 */
function kindOf(span: ReadableSpan, type: string | undefined): CauselineSpanKind {
  if (type === 'BaseServer.handleRequest' || span.kind === SpanKind.SERVER) {
    return 'REQUEST';
  }
  if (type === 'AppRender.fetch' || span.kind === SpanKind.CLIENT) {
    return 'HTTP_CLIENT';
  }
  if (type === 'AppRouteRouteHandlers.runHandler' || type === 'Node.runHandler') {
    return 'CONTROLLER';
  }
  if (type?.startsWith('AppRender.') || type?.startsWith('Render.') || type?.startsWith('ResolveMetadata.')
    || type?.includes('renderToReadableStream') || /^render /.test(span.name)) {
    return 'RENDER';
  }
  return 'SERVICE';
}

/** The first stack frame outside node_modules and Node's internals: where in the app it happened. */
function appFrame(stack: string): string | undefined {
  for (const line of stack.split('\n').slice(1)) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('at ') || /node_modules|node:internal|\(native\)|next\/dist/.test(trimmed)) {
      continue;
    }
    // "at fn (location)" or "at location"; the location itself may contain parentheses, e.g. (rsc).
    const open = trimmed.indexOf(' (');
    const location = open >= 0 && trimmed.endsWith(')') ? trimmed.slice(open + 2, -1) : trimmed.slice(3);
    return location.replace(/^webpack-internal:\/\/\/(\([^)]*\)\/)?/, '').replace(/^file:\/\//, '');
  }
  return undefined;
}

function splitQuery(target: string): [string, string | undefined] {
  const i = target.indexOf('?');
  return i < 0 ? [target, undefined] : [target.slice(0, i), target.slice(i + 1)];
}

function parse(url: string): URL | undefined {
  try {
    return new URL(url);
  } catch {
    return undefined;
  }
}

function nanos(time: HrTime): bigint {
  return BigInt(time[0]) * 1_000_000_000n + BigInt(time[1]);
}

function randomSpanId(): string {
  const bytes = new Uint8Array(8);
  globalThis.crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}
