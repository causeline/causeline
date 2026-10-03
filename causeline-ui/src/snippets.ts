// SPDX-License-Identifier: Apache-2.0
import type { SpanRow, TraceView } from './api';

/** Headers a client sets by itself, or that only make sense for the original connection. */
const SKIPPED_HEADERS = new Set([
  'host',
  'content-length',
  'connection',
  'accept-encoding',
  'traceparent',
  'tracestate',
  'baggage',
  'x-causeline-replay',
  'x-causeline-replay-session',
]);

export interface CapturedRequest {
  method: string;
  /** Path and query, e.g. /api/orders?draft=true */
  target: string;
  /** Full URL when the browser recorded one */
  url?: string;
  headers: [string, string][];
  body?: string;
  status?: number;
}

/** The request a REQUEST span describes, or undefined when it isn't one. */
export function capturedRequest(span: SpanRow): CapturedRequest | undefined {
  const a = span.attributes;
  const method = a['http.request.method'];
  if (span.kind !== 'REQUEST' || !method) {
    return undefined;
  }
  const path = a['url.path'] ?? (a['url.full'] ? new URL(a['url.full']).pathname : undefined) ?? a['http.route'] ?? '/';
  const query = a['url.query'] ?? (a['url.full'] ? new URL(a['url.full']).search.slice(1) : '');
  const headers = Object.entries(a)
    .filter(([key]) => key.startsWith('http.request.header.'))
    .map(([key, value]) => [key.slice('http.request.header.'.length), value] as [string, string])
    .filter(([name]) => !SKIPPED_HEADERS.has(name));
  const body = a['http.request.body'];
  const status = Number(a['http.response.status_code']);
  return {
    method,
    target: query ? `${path}?${query}` : path,
    url: a['url.full'],
    headers,
    // "[2048 bytes, image/png]" describes a body that wasn't recorded as text.
    body: body && !/^\[\d+ bytes/.test(body) ? body : undefined,
    status: Number.isFinite(status) && status > 0 ? status : undefined,
  };
}

const shellQuote = (value: string) => `'${value.replace(/'/g, `'\\''`)}'`;

/** A cURL command that sends the request again, with the headers and body that were captured. */
export function toCurl(request: CapturedRequest, origin: string): string {
  const url = request.url ?? origin.replace(/\/$/, '') + request.target;
  const lines = [`curl -X ${request.method} ${shellQuote(url)}`];
  for (const [name, value] of request.headers) {
    lines.push(`  -H ${shellQuote(`${name}: ${value}`)}`);
  }
  if (request.body !== undefined) {
    lines.push(`  --data-raw ${shellQuote(request.body)}`);
  }
  return lines.join(' \\\n');
}

const javaString = (value: string) =>
  `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n').replace(/\r/g, '\\r')}"`;

/** What a captured request did, as far as a test can check it again on any machine. */
export interface TraceGuard {
  /** The request span's name, e.g. POST /api/orders: picks its trace if the test records several */
  request: string;
  /** Exceptions thrown or logged; when empty and nothing failed, the test expects no failures at all */
  exceptions: string[];
  failed: boolean;
  queries: number;
  /** The trace already had an N+1, so the test can't demand there is none */
  repeatedQueries: boolean;
  /** Controller, service and repository methods the request went through */
  spans: string[];
}

const SHAPE_KINDS = new Set(['CONTROLLER', 'SERVICE', 'REPOSITORY']);

/** Summarises the part of a trace under a request span, for the assertions of a generated test. */
export function traceGuard(request: SpanRow, trace: Pick<TraceView, 'spans' | 'insights'>): TraceGuard {
  const start = trace.spans.findIndex((s) => s.spanId === request.spanId);
  const under: SpanRow[] = [];
  for (let i = start + 1; start >= 0 && i < trace.spans.length; i++) {
    const row = trace.spans[i]!;
    if (row.depth <= request.depth) {
      break;
    }
    under.push(row);
  }
  const all = start >= 0 ? [request, ...under] : [request];
  const ids = new Set(all.map((s) => s.spanId));
  const byId = new Map(all.map((s) => [s.spanId, s]));
  // Work handed to another thread (@Async) outlives its parent and may finish after a test has
  // looked, so it is left out of the spans a test must find.
  const detached = new Set<string>();
  for (const row of under) {
    const parent = byId.get(row.parentSpanId ?? '');
    const outlivesParent = parent && row.offsetNanos + row.durationNanos > parent.offsetNanos + parent.durationNanos;
    if (outlivesParent || detached.has(row.parentSpanId ?? '')) {
      detached.add(row.spanId);
    }
  }
  const exceptions = all.filter((s) => s.kind === 'EXCEPTION').map((s) => s.name);
  return {
    request: request.name,
    exceptions: [...new Set(exceptions)],
    failed: exceptions.length > 0 || all.some((s) => s.status === 'ERROR'),
    queries: all.filter((s) => s.kind === 'DATABASE').length,
    repeatedQueries: trace.insights.some((i) => i.rule === 'REPEATED_QUERY' && ids.has(i.spanId)),
    spans: [...new Set(under.filter((s) => SHAPE_KINDS.has(s.kind) && !detached.has(s.spanId)).map((s) => s.name))],
  };
}

/** The guard's assertions as Java statements, or nothing when there is no guard. */
function guardLines(guard: TraceGuard | undefined): string {
  if (!guard) {
    return '';
  }
  const checks = guard.failed ? guard.exceptions.map((e) => `.hasException(${javaString(e)})`) : ['.hasNoFailedSpans()'];
  checks.push(`.hasQueryCountAtMost(${guard.queries})`);
  if (!guard.repeatedQueries) {
    checks.push('.hasNoRepeatedQueries()');
  }
  if (guard.spans.length > 0) {
    checks.push(`.hasSpans(${guard.spans.map(javaString).join(', ')})`);
  }
  return `

        // What the application did for this request when it was captured, not only what it answered.
        causeline.trace(${javaString(guard.request)})
${checks.map((c) => `                ${c}`).join('\n')};`;
}

/** The lines of a generated test that differ with and without the guard. */
function guardParts(guard: TraceGuard | undefined) {
  return guard
    ? {
        needs: '\n// Needs dev.causeline:causeline-test in test scope, for the trace assertions.',
        imports: 'import dev.causeline.test.CauselineTest;\nimport dev.causeline.test.RecordedTraces;\n',
        annotation: '\n@CauselineTest',
        parameter: 'RecordedTraces causeline',
      }
    : { needs: '', imports: '', annotation: '', parameter: '' };
}

/** Credentials are left out of generated tests: they would end up committed to the repository. */
const isCredential = (name: string) => /authorization|cookie|token|secret|api-key|session/i.test(name);

/**
 * Headers a browser adds to every request by itself. They say nothing about what the request asks
 * for, so a generated test leaves them out and can be pasted as it is. (cURL keeps them: it resends
 * the request exactly.)
 */
const BROWSER_HEADERS = /^(sec-.*|user-agent|referer|origin|accept-language|priority|dnt|pragma|upgrade-insecure-requests)$/;

/** Whether a captured header belongs in a generated test. */
const inTest = ([name, value]: [string, string]) =>
  name !== 'content-type' && !isCredential(name) && !BROWSER_HEADERS.test(name) && !(name === 'accept' && value.includes('*/*'));

function testName(request: CapturedRequest): string {
  const words = request.target.split('?')[0]?.split('/').filter((w) => w && !/^\d+$/.test(w)) ?? [];
  const tail = words.map((w) => w.replace(/[^A-Za-z0-9]/g, '')).map((w) => w.charAt(0).toUpperCase() + w.slice(1));
  return `${request.method.toLowerCase()}${tail.join('')}Replays`;
}

/**
 * A JUnit test for a Spring MVC application, using MockMvc. It expects the status the original
 * got and, given a guard, that the application does what the captured trace did.
 */
export function toMockMvcTest(request: CapturedRequest, guard?: TraceGuard): string {
  const g = guardParts(guard);
  const call = [`        mockMvc.perform(request(HttpMethod.${request.method}, ${javaString(request.target)})`];
  const contentType = request.headers.find(([n]) => n === 'content-type')?.[1];
  for (const [name, value] of request.headers.filter(inTest)) {
    call.push(`                .header(${javaString(name)}, ${javaString(value)})`);
  }
  if (request.body !== undefined) {
    call.push(`                .contentType(${javaString(contentType ?? 'application/json')})`);
    call.push(`                .content(${javaString(request.body)}))`);
  } else {
    call[call.length - 1] += ')';
  }
  call.push(`                .andExpect(status().is(${request.status ?? 200}));`);
  return `// Generated by Causeline from a captured request. Credentials are left out.
// Needs org.springframework.boot:spring-boot-starter-webmvc-test in test scope.${g.needs}
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

${g.imports}import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc${g.annotation}
class ${capitalize(testName(request))}Test {

    @Autowired
    MockMvc mockMvc;

    @Test
    void ${testName(request)}(${g.parameter}) throws Exception {
${call.join('\n')}${guardLines(guard)}
    }
}
`;
}

/** A JUnit test for a WebFlux application, using WebTestClient. */
export function toWebTestClientTest(request: CapturedRequest, guard?: TraceGuard): string {
  const g = guardParts(guard);
  const call = [`        client.method(HttpMethod.${request.method}).uri(${javaString(request.target)})`];
  const contentType = request.headers.find(([n]) => n === 'content-type')?.[1];
  for (const [name, value] of request.headers.filter(inTest)) {
    call.push(`                .header(${javaString(name)}, ${javaString(value)})`);
  }
  if (request.body !== undefined) {
    call.push(`                .contentType(MediaType.parseMediaType(${javaString(contentType ?? 'application/json')}))`);
    call.push(`                .bodyValue(${javaString(request.body)})`);
  }
  call.push('                .exchange()');
  call.push(`                .expectStatus().isEqualTo(${request.status ?? 200});`);
  return `// Generated by Causeline from a captured request. Credentials are left out.
// Needs org.springframework.boot:spring-boot-starter-webflux-test in test scope.${g.needs}
${g.imports}import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
@AutoConfigureWebTestClient${g.annotation}
class ${capitalize(testName(request))}Test {

    @Autowired
    WebTestClient client;

    @Test
    void ${testName(request)}(${g.parameter}) {
${call.join('\n')}${guardLines(guard)}
    }
}
`;
}

const capitalize = (value: string) => value.charAt(0).toUpperCase() + value.slice(1);
