// SPDX-License-Identifier: Apache-2.0
import { buildReport } from './report';
import type { ExportedTrace } from './report';

export type SpanStatus = 'OK' | 'ERROR' | 'UNSET';

export type SpanKind =
  | 'UI_ACTION'
  | 'REQUEST'
  | 'CONTROLLER'
  | 'SERVICE'
  | 'REPOSITORY'
  | 'DATABASE'
  | 'HTTP_CLIENT'
  | 'EXCEPTION'
  | 'STATE_UPDATE'
  | 'RENDER'
  | 'MESSAGE'
  | 'LOG'
  | 'TRANSACTION'
  | 'CACHE';

export interface TraceSummary {
  traceId: string;
  name: string;
  status: SpanStatus;
  startTimeUnixNano: number;
  durationNanos: number;
  spanCount: number;
  /** The original trace this one replays, or null. */
  replayOf: string | null;
  /** Loaded from an exported file rather than recorded here. */
  imported: boolean;
  /** The usual duration of this action (median of other successful runs), or null without enough history. */
  baselineNanos?: number | null;
  baselineRuns?: number;
}

export interface Status {
  traces: number;
  estimatedBytes: number;
  maxBytes: number;
  evictedTraces: number;
  serverSpansDropped: number;
  browserSpansDropped: number;
  browserSpansRejected: number;
  otlp: { enabled: boolean; endpointHost: string | null; exported: number; dropped: number; failedRequests: number };
  onboarding: Onboarding;
  /** The Causeline this service sends spans to, and spans received from downstream services. */
  upstream?: { enabled: boolean; host: string | null; sent: number; dropped: number; failedRequests: number; received: number };
}

/** What has arrived since the application started, for the first-run checklist. */
export interface Onboarding {
  appName: string;
  serverSpans: number;
  browserSpans: number;
  namedActions: number;
  /** Epoch milliseconds, or null when nothing has arrived yet. */
  lastServerSpanAt: number | null;
  lastBrowserSpanAt: number | null;
}

export interface SpanRow {
  spanId: string;
  parentSpanId: string | null;
  kind: SpanKind;
  name: string;
  source: string;
  status: SpanStatus;
  depth: number;
  offsetNanos: number;
  durationNanos: number;
  selfNanos: number;
  clockSkew: boolean;
  attributes: Record<string, string>;
}

export type InsightRule =
  | 'PRIMARY_BOTTLENECK'
  | 'NOTABLE'
  | 'REPEATED_QUERY'
  | 'SLOW_CLIENT_HANDLING'
  | 'HANDLED_EXCEPTION';

export interface Insight {
  rule: InsightRule;
  spanId: string;
  label: string;
  sharePercent: number;
}

export interface TraceView {
  traceId: string;
  name: string;
  status: SpanStatus;
  startTimeUnixNano: number;
  durationNanos: number;
  spans: SpanRow[];
  insights: Insight[];
}

const BASE = '/causeline/api';

/** Thrown when the API refuses the token, so the UI can explain how to get a valid one. */
export class UnauthorizedError extends Error {
  constructor() {
    super('Missing or expired access token');
  }
}

async function getJson<T>(path: string, token: string | undefined): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (token) {
    headers['X-Causeline-Token'] = token;
  }
  const response = await fetch(BASE + path, { headers });
  if (response.status === 401) {
    throw new UnauthorizedError();
  }
  if (!response.ok) {
    throw new Error(`${path}: HTTP ${response.status}`);
  }
  return (await response.json()) as T;
}

export interface ReplayTarget {
  name: string;
  location: string;
  auth: string;
  /** Whether the replay's trace can be fetched for a span-by-span comparison. */
  comparable: boolean;
  /** Whether the original request's credential headers are resent. */
  sendsOriginalCredentials: boolean;
}

export interface Replayable {
  spanId: string;
  method: string;
  route: string;
  unsafe: boolean;
  hasBody: boolean;
  bodyBytes: number;
  bodyMissing: boolean;
  queryMissing: boolean;
}

export interface ReplayOutcome {
  replayTraceId: string;
  target: string;
  httpStatus: number;
  durationNanos: number;
  sentRedactedFields: boolean;
}

export type Change = 'SAME' | 'FASTER' | 'SLOWER' | 'STATUS_CHANGED' | 'ONLY_IN_ORIGINAL' | 'ONLY_IN_REPLAY';

export interface ComparisonRow {
  depth: number;
  kind: SpanKind;
  name: string;
  originalNanos: number | null;
  replayNanos: number | null;
  originalStatus: SpanStatus | null;
  replayStatus: SpanStatus | null;
  change: Change;
}

export interface ComparisonSide {
  traceId: string;
  status: SpanStatus;
  durationNanos: number;
  httpStatus: string | null;
}

export interface Comparison {
  instrumented: boolean;
  result: { original: ComparisonSide; replay: ComparisonSide; rows: ComparisonRow[] } | null;
}

/** A refusal from the replay API, with the server's explanation. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function send<T>(method: string, path: string, token: string | undefined, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (token) {
    headers['X-Causeline-Token'] = token;
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  if (response.status === 401) {
    throw new UnauthorizedError();
  }
  if (!response.ok) {
    const error = (await response.json().catch(() => ({}))) as { error?: string };
    throw new ApiError(response.status, error.error ?? `HTTP ${response.status}`);
  }
  return (await response.json()) as T;
}

export const fetchStatus = (token: string | undefined) => send<Status>('GET', '/status', token);

/** Downloads a trace as a JSON file, using the access token (a plain link could not send it). */
export async function downloadTrace(traceId: string, token: string | undefined): Promise<void> {
  const response = await fetch(`${BASE}/traces/${encodeURIComponent(traceId)}/export`, {
    headers: token ? { 'X-Causeline-Token': token } : {},
  });
  if (!response.ok) {
    throw new ApiError(response.status, `Export failed: HTTP ${response.status}`);
  }
  const name = /filename="([^"]+)"/.exec(response.headers.get('Content-Disposition') ?? '')?.[1] ?? 'causeline-trace.json';
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
}

/**
 * Downloads a self-contained HTML bug report. Values come from the redacted export, never from the
 * unredacted view, so the file carries no more than an exported trace would.
 */
export async function downloadReport(traceId: string, token: string | undefined): Promise<void> {
  const headers: Record<string, string> = token ? { 'X-Causeline-Token': token } : {};
  const [view, exported] = await Promise.all([
    fetchTrace(traceId, token),
    fetch(`${BASE}/traces/${encodeURIComponent(traceId)}/export`, { headers }).then(async (r) => {
      if (!r.ok) {
        throw new ApiError(r.status, `Export failed: HTTP ${r.status}`);
      }
      return (await r.json()) as ExportedTrace;
    }),
  ]);
  const url = URL.createObjectURL(new Blob([buildReport(view, exported)], { type: 'text/html' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `causeline-report-${traceId.slice(0, 8)}.html`;
  link.click();
  URL.revokeObjectURL(url);
}

/** Loads an exported trace file; returns the trace ID. */
export async function importTrace(fileContent: string, token: string | undefined): Promise<string> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(fileContent);
  } catch {
    throw new ApiError(400, 'That file is not valid JSON.');
  }
  const result = await send<{ traceId: string }>('POST', '/traces/import', token, parsed);
  return result.traceId;
}

export const fetchReplayTargets =(token: string | undefined) => send<ReplayTarget[]>('GET', '/replay/targets', token);

export const fetchReplayable = (traceId: string, token: string | undefined) =>
  send<Replayable[]>('GET', `/traces/${encodeURIComponent(traceId)}/replayable`, token);

export const replay = (
  request: { traceId: string; spanId: string; target: string; confirm: boolean; body?: string },
  token: string | undefined,
) => send<ReplayOutcome>('POST', '/replays', token, request);

export const fetchComparison = (
  query: { traceId: string; spanId: string; replayTraceId: string; target: string },
  token: string | undefined,
) => send<Comparison>('GET', `/replays/compare?${new URLSearchParams(query).toString()}`, token);

export const fetchTraces = (token: string | undefined): Promise<TraceSummary[]> => getJson('/traces', token);

export const fetchTrace = (traceId: string, token: string | undefined): Promise<TraceView> =>
  getJson(`/traces/${encodeURIComponent(traceId)}`, token);

/** Where an application class's source file is on this machine. */
export interface SourceLocation {
  path: string;
  line: number;
}

/** Looks up a class's source file to open it in the editor; undefined when it isn't in the project. */
export async function fetchSource(
  query: { className: string; method?: string; line?: number },
  token: string | undefined,
): Promise<SourceLocation | undefined> {
  const params = new URLSearchParams({ class: query.className });
  if (query.method) {
    params.set('method', query.method);
  }
  if (query.line) {
    params.set('line', String(query.line));
  }
  try {
    return await send<SourceLocation>('GET', `/source?${params.toString()}`, token);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      return undefined;
    }
    throw e;
  }
}

export interface SearchMatch {
  spanId: string;
  spanName: string;
  /** "name", or the attribute that matched, e.g. http.request.body */
  field: string;
  excerpt: string;
}

export interface SearchHit {
  traceId: string;
  startTimeUnixNano: number;
  matches: SearchMatch[];
}

/** Traces whose span names or captured values contain `q`. */
export const searchTraces = (q: string, token: string | undefined) =>
  send<SearchHit[]>('GET', `/search?${new URLSearchParams({ q }).toString()}`, token);

export type ComparisonResult = NonNullable<Comparison['result']>;

/** Two recorded runs compared span by span: `a` is the baseline. */
export const compareTraces = (a: string, b: string, token: string | undefined) =>
  send<ComparisonResult>('GET', `/compare?${new URLSearchParams({ a, b }).toString()}`, token);

export interface PausedArgument {
  name: string;
  type: string;
  json: string;
  editable: boolean;
}

export interface ReplaySession {
  id: string;
  state: 'RUNNING' | 'PAUSED' | 'DONE' | 'FAILED';
  replayTraceId: string | null;
  paused: { step: string; occurrence: number; arguments: PausedArgument[]; since: string } | null;
  outcome: ReplayOutcome | null;
  error: string | null;
  /** Steps whose arguments were changed, e.g. "OrderService.createOrder #1". */
  edited: string[];
}

/** Starts a replay of this application that stops at the chosen steps (span IDs of the original). */
export const startReplaySession = (
  request: { traceId: string; spanId: string; confirm: boolean; body?: string; breakpoints: string[] },
  token: string | undefined,
) => send<ReplaySession>('POST', '/replay-sessions', token, { ...request, target: 'local' });

export const fetchReplaySession = (id: string, token: string | undefined) =>
  send<ReplaySession>('GET', `/replay-sessions/${encodeURIComponent(id)}`, token);

/** Continues a paused replay; `arguments` holds the JSON of the arguments to change, by name. */
export const continueReplaySession = (
  id: string,
  request: { arguments: Record<string, string>; skipRest?: boolean },
  token: string | undefined,
) => send<ReplaySession>('POST', `/replay-sessions/${encodeURIComponent(id)}/continue`, token, request);
