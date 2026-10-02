// SPDX-License-Identifier: Apache-2.0
import { useState } from 'react';
import { downloadTrace } from './api';
import type { Insight, SpanKind, SpanRow, TraceView } from './api';
import { formatDuration, percent } from './format';
import { ReplayPanel } from './ReplayPanel';

const KIND_LABEL: Record<SpanKind, string> = {
  UI_ACTION: 'Action',
  REQUEST: 'Request',
  CONTROLLER: 'Controller',
  SERVICE: 'Service',
  REPOSITORY: 'Repository',
  DATABASE: 'Database',
  HTTP_CLIENT: 'HTTP client',
  EXCEPTION: 'Exception',
  STATE_UPDATE: 'State',
  RENDER: 'Render',
  MESSAGE: 'Message',
};

const INSIGHT_STYLE: Record<Insight['rule'], string> = {
  PRIMARY_BOTTLENECK: 'border-red-500/40 bg-red-500/10',
  NOTABLE: 'border-amber-500/40 bg-amber-500/10',
  REPEATED_QUERY: 'border-amber-500/40 bg-amber-500/10',
  SLOW_CLIENT_HANDLING: 'border-sky-500/40 bg-sky-500/10',
  HANDLED_EXCEPTION: 'border-neutral-400/50 bg-neutral-500/10',
};

const isBrowser = (span: SpanRow) => span.source === 'browser';
const isInferred = (span: SpanRow) => span.attributes['causeline.link'] === 'inferred';
const isHandled = (span: SpanRow) => span.attributes['causeline.exception.handled'] === 'true';

function barColor(span: SpanRow): string {
  if (span.status === 'ERROR') {
    return 'bg-red-500';
  }
  if (isHandled(span)) {
    return 'bg-neutral-400';
  }
  return isBrowser(span) ? 'bg-sky-500' : 'bg-violet-500';
}

export function Timeline({
  trace,
  token,
  replayOf,
  onOpenTrace,
}: {
  trace: TraceView;
  token?: string;
  /** Set when this trace is a replay: the original's ID and a readable label. */
  replayOf?: { traceId: string; label: string };
  onOpenTrace?: (traceId: string) => void;
}) {
  const total = Math.max(trace.durationNanos, 1);
  const [selectedId, setSelectedId] = useState<string>();
  const selected = trace.spans.find((s) => s.spanId === selectedId);
  const flagged = new Map(trace.insights.map((i) => [i.spanId, i]));
  const exceptions = trace.spans.filter((s) => s.kind === 'EXCEPTION');

  return (
    <section aria-label={`Timeline for ${trace.name}`}>
      <header className="flex flex-wrap items-baseline gap-x-4 gap-y-1 border-b border-neutral-200 px-6 py-4 dark:border-neutral-800">
        <h2 className="text-lg font-semibold">{trace.name}</h2>
        <span className="text-sm text-neutral-500 dark:text-neutral-400">
          {formatDuration(trace.durationNanos)} · {trace.spans.length} spans
        </span>
        {trace.status === 'ERROR' && <span className="text-sm font-medium text-red-600">Failed</span>}
        {replayOf && (
          <button
            type="button"
            onClick={() => onOpenTrace?.(replayOf.traceId)}
            className="text-sm text-violet-600 underline-offset-2 hover:underline dark:text-violet-400"
          >
            Replay of {replayOf.label}
          </button>
        )}
        <code className="ml-auto text-xs text-neutral-400">trace {trace.traceId}</code>
        <button
          type="button"
          onClick={() => void downloadTrace(trace.traceId, token).catch(() => {})}
          title="Save this trace as a JSON file, e.g. to attach to a bug report. It contains the redacted spans shown here."
          className="rounded border border-neutral-300 px-2 py-0.5 text-xs hover:bg-neutral-100 dark:border-neutral-700 dark:hover:bg-neutral-900"
        >
          Export
        </button>
      </header>

      <ReplayPanel key={trace.traceId} traceId={trace.traceId} token={token} />

      {trace.insights.length > 0 && (
        <ul aria-label="Insights" className="space-y-2 px-6 pt-4">
          {trace.insights.map((insight) => (
            <li key={insight.rule + insight.spanId}>
              <button
                type="button"
                onClick={() => setSelectedId(insight.spanId)}
                className={`w-full rounded border px-3 py-2 text-left text-sm ${INSIGHT_STYLE[insight.rule]}`}
              >
                {insight.label}
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="px-6 py-3 text-xs text-neutral-500 dark:text-neutral-400">
        <span className="mr-4 inline-flex items-center gap-1">
          <span className="inline-block h-2 w-3 rounded-sm bg-sky-500" /> Browser
        </span>
        <span className="mr-4 inline-flex items-center gap-1">
          <span className="inline-block h-2 w-3 rounded-sm bg-violet-500" /> Server
        </span>
        <span className="inline-flex items-center gap-1">
          <span className="inline-block h-2 w-3 rounded-sm bg-red-500" /> Failed
        </span>
      </div>

      <table className="w-full table-fixed text-sm">
        <thead>
          <tr className="text-left text-xs text-neutral-500 dark:text-neutral-400">
            <th className="w-[45%] px-6 py-2 font-medium">Operation</th>
            <th className="px-2 py-2 font-medium">Timeline</th>
            <th className="w-20 px-2 py-2 text-right font-medium">Duration</th>
            <th className="w-32 px-6 py-2 text-right font-medium">Self</th>
          </tr>
        </thead>
        <tbody>
          {trace.spans.map((span) => {
            const left = (span.offsetNanos / total) * 100;
            const width = Math.max((span.durationNanos / total) * 100, 0.4);
            const insight = flagged.get(span.spanId);
            const active = span.spanId === selectedId;
            return (
              <tr
                key={span.spanId}
                onClick={() => setSelectedId(active ? undefined : span.spanId)}
                aria-selected={active}
                className={`cursor-pointer border-t border-neutral-100 hover:bg-neutral-50 dark:border-neutral-900 dark:hover:bg-neutral-900 ${
                  active ? 'bg-neutral-100 dark:bg-neutral-900' : ''
                }`}
              >
                <td className="px-6 py-1.5">
                  <div className="flex items-center gap-2" style={{ paddingLeft: span.depth * 16 }}>
                    <span className="w-16 shrink-0 text-[11px] text-neutral-500 dark:text-neutral-400">
                      {KIND_LABEL[span.kind]}
                    </span>
                    <span className={`truncate ${span.status === 'ERROR' ? 'text-red-600' : ''}`} title={span.name}>
                      {span.name}
                    </span>
                    {isHandled(span) && (
                      <span
                        className="shrink-0 rounded border border-neutral-400 px-1 text-[10px] text-neutral-500"
                        title="Caught and logged by the application; the request continued"
                      >
                        handled
                      </span>
                    )}
                    {insight?.rule === 'PRIMARY_BOTTLENECK' && (
                      <span className="shrink-0 rounded bg-red-600 px-1 text-[10px] font-medium text-white">
                        bottleneck
                      </span>
                    )}
                    {span.attributes['causeline.inferred_cause'] && (
                      <span
                        className="shrink-0 truncate text-[10px] text-neutral-500"
                        title="Inferred from timing: this request followed the click within a second. It is not linked to it."
                      >
                        after {span.attributes['causeline.inferred_cause']}?
                      </span>
                    )}
                    {isInferred(span) && (
                      <span className="shrink-0 text-[10px] text-neutral-500" title="Linked by timing, not by an explicit trace() call">
                        inferred
                      </span>
                    )}
                    {span.clockSkew && (
                      <span className="shrink-0 text-xs text-amber-600" title="Placed at the parent's start: this span was longer than its parent">
                        skew
                      </span>
                    )}
                  </div>
                </td>
                <td className="px-2 py-1.5">
                  <div className="relative h-3">
                    <div
                      className={`absolute top-0 h-3 rounded-sm ${barColor(span)} ${isInferred(span) ? 'opacity-60' : ''}`}
                      style={{ left: `${left}%`, width: `${Math.min(width, 100 - left)}%` }}
                      title={`${span.name}: starts at ${formatDuration(span.offsetNanos)}`}
                    />
                  </div>
                </td>
                <td className="px-2 py-1.5 text-right whitespace-nowrap tabular-nums">
                  {formatDuration(span.durationNanos)}
                </td>
                <td className="px-6 py-1.5 text-right whitespace-nowrap tabular-nums text-neutral-500 dark:text-neutral-400">
                  {formatDuration(span.selfNanos)}
                  <span className="ml-1 text-xs">({percent(span.selfNanos, total)}%)</span>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {selected && <SpanDetail span={selected} />}
      {exceptions.some((e) => !isHandled(e)) && (
        <ExceptionPanel exceptions={exceptions.filter((e) => !isHandled(e))} handled={false} />
      )}
      {exceptions.some(isHandled) && <ExceptionPanel exceptions={exceptions.filter(isHandled)} handled />}
    </section>
  );
}

/** Long, multi-line values shown in their own block below the attribute list. */
const BLOCK_ATTRIBUTES = new Set([
  'exception.stacktrace',
  'http.request.body',
  'http.response.body',
  'db.query.text',
  'db.query.statement',
]);

function prettyBody(value: string): string {
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

export function SpanDetail({ span }: { span: SpanRow }) {
  const entries = Object.entries(span.attributes).filter(([key]) => !BLOCK_ATTRIBUTES.has(key));
  const body = span.attributes['http.request.body'];
  const responseBody = span.attributes['http.response.body'];
  const sql = span.attributes['db.query.text'];
  const prepared = span.attributes['db.query.statement'];
  return (
    <section aria-label="Span detail" className="mx-6 mt-4 rounded border border-neutral-200 p-4 text-sm dark:border-neutral-800">
      <h3 className="font-semibold">
        {KIND_LABEL[span.kind]}: {span.name}
      </h3>
      <dl className="mt-2 grid grid-cols-[10rem_1fr] gap-x-4 gap-y-1 text-xs">
        <dt className="text-neutral-500">Source</dt>
        <dd>{span.source}</dd>
        <dt className="text-neutral-500">Status</dt>
        <dd className={span.status === 'ERROR' ? 'text-red-600' : ''}>{span.status}</dd>
        <dt className="text-neutral-500">Starts at</dt>
        <dd>{formatDuration(span.offsetNanos)}</dd>
        <dt className="text-neutral-500">Duration / self</dt>
        <dd>
          {formatDuration(span.durationNanos)} / {formatDuration(span.selfNanos)}
        </dd>
        {entries.map(([key, value]) => (
          <Attribute key={key} name={key} value={value} />
        ))}
      </dl>
      {sql && <Block title={prepared ? 'SQL (with values)' : 'SQL'} value={sql} />}
      {prepared && <Block title="SQL as prepared" value={prepared} />}
      {body && <Block title="Request body" value={prettyBody(body)} />}
      {responseBody && <Block title="Response body" value={prettyBody(responseBody)} />}
    </section>
  );
}

function Block({ title, value }: { title: string; value: string }) {
  return (
    <div className="mt-3">
      <div className="text-xs text-neutral-500">{title}</div>
      <pre className="mt-1 max-h-64 overflow-auto rounded bg-neutral-100 p-2 text-[11px] whitespace-pre-wrap break-all dark:bg-neutral-900">
        {value}
      </pre>
    </div>
  );
}

function Attribute({ name, value }: { name: string; value: string }) {
  return (
    <>
      <dt className="truncate text-neutral-500" title={name}>
        {name}
      </dt>
      <dd className="break-all">{value}</dd>
    </>
  );
}

function ExceptionPanel({ exceptions, handled }: { exceptions: SpanRow[]; handled: boolean }) {
  const title = handled
    ? `${exceptions.length === 1 ? 'Handled exception' : `${exceptions.length} handled exceptions`}: caught and logged, request continued`
    : exceptions.length === 1
      ? 'Exception'
      : `${exceptions.length} exceptions`;
  return (
    <section
      aria-label={handled ? 'Handled exceptions' : 'Exceptions'}
      className={`mx-6 my-4 rounded border p-4 text-sm ${
        handled ? 'border-neutral-400/50 bg-neutral-500/5' : 'border-red-500/40 bg-red-500/5'
      }`}
    >
      <h3 className={`font-semibold ${handled ? 'text-neutral-600 dark:text-neutral-300' : 'text-red-600'}`}>{title}</h3>
      <ul className="mt-2 space-y-3">
        {exceptions.map((e) => (
          <li key={e.spanId}>
            <div className="font-medium">{e.attributes['exception.type'] ?? e.name}</div>
            {e.attributes['code.location'] && (
              <div className="text-xs text-neutral-600 dark:text-neutral-400">
                at {e.attributes['code.function']} ({e.attributes['code.location']})
              </div>
            )}
            {handled && e.attributes['log.logger'] && (
              <div className="text-xs text-neutral-600 dark:text-neutral-400">
                logged at {e.attributes['log.level']} by {e.attributes['log.logger']}
              </div>
            )}
            {e.attributes['log.message'] && <div className="mt-1 text-xs">Log: {e.attributes['log.message']}</div>}
            {e.attributes['exception.message'] && <div className="mt-1 text-xs">{e.attributes['exception.message']}</div>}
            {e.attributes['exception.stacktrace'] ? (
              <details className="mt-1 text-xs">
                <summary className="cursor-pointer text-neutral-500">Stack trace</summary>
                <pre className="mt-1 max-h-64 overflow-auto whitespace-pre text-[11px]">
                  {e.attributes['exception.stacktrace']}
                </pre>
              </details>
            ) : (
              <div className="mt-1 text-xs text-neutral-500">
                Message and stack trace are hidden. Set <code>causeline.capture.exception-details=true</code> to record
                them.
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
