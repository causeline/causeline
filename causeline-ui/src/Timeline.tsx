// SPDX-License-Identifier: Apache-2.0
import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { compareTraces, downloadReport, downloadTrace, fetchSource } from './api';
import type { ComparisonResult, Insight, SpanKind, SpanRow, TraceSummary, TraceView } from './api';
import { EDITORS, openInEditor, useEditor } from './editor';
import type { Editor } from './editor';
import { formatClock, formatDuration, percent } from './format';
import { ComparisonTable, ReplayPanel } from './ReplayPanel';
import { capturedRequest, toCurl, toMockMvcTest, toWebTestClientTest } from './snippets';
import { SLOW_FACTOR, slowdown } from './TraceList';

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
  LOG: 'Log',
  TRANSACTION: 'Transaction',
  CACHE: 'Cache',
};

const INSIGHT_STYLE: Record<Insight['rule'], { accent: string; tag: string }> = {
  PRIMARY_BOTTLENECK: { accent: 'bg-signal', tag: 'Bottleneck' },
  NOTABLE: { accent: 'bg-warn', tag: 'Notable' },
  REPEATED_QUERY: { accent: 'bg-warn', tag: 'Repeated query' },
  SLOW_CLIENT_HANDLING: { accent: 'bg-browser', tag: 'Browser' },
  HANDLED_EXCEPTION: { accent: 'bg-muted', tag: 'Handled' },
};

const isBrowser = (span: SpanRow) => span.source === 'browser';
const isInferred = (span: SpanRow) => span.attributes['causeline.link'] === 'inferred';
const isHandled = (span: SpanRow) => span.attributes['causeline.exception.handled'] === 'true';

function barColor(span: SpanRow, bottleneck: boolean): string {
  if (span.status === 'ERROR') {
    return 'bg-error';
  }
  if (bottleneck) {
    return 'bg-signal';
  }
  if (isHandled(span)) {
    return 'bg-muted';
  }
  return isBrowser(span) ? 'bg-browser' : 'bg-server';
}

function kindColor(span: SpanRow): string {
  if (span.kind === 'EXCEPTION') {
    return isHandled(span) ? 'text-muted' : 'text-error';
  }
  return isBrowser(span) ? 'text-browser' : 'text-server';
}

const TICKS = [0, 0.25, 0.5, 0.75, 1];

export type DetailPlacement = 'side' | 'below';
const PLACEMENT_KEY = 'causeline-ui-detail';

/** Where span details open: beside the waterfall (default) or under it. Remembered per browser. */
function useDetailPlacement(): [DetailPlacement, (p: DetailPlacement) => void] {
  const [placement, setPlacement] = useState<DetailPlacement>(() => {
    try {
      return typeof window !== 'undefined' && localStorage.getItem(PLACEMENT_KEY) === 'below' ? 'below' : 'side';
    } catch {
      return 'side';
    }
  });
  const choose = useCallback((next: DetailPlacement) => {
    setPlacement(next);
    try {
      localStorage.setItem(PLACEMENT_KEY, next);
    } catch {
      // Not remembered; it still applies now.
    }
  }, []);
  return [placement, choose];
}

export function Timeline({
  trace,
  token,
  usual,
  replayOf,
  onOpenTrace,
  traces = [],
}: {
  trace: TraceView;
  token?: string;
  /** Other recorded traces, to compare this one with. */
  traces?: TraceSummary[];
  /** This trace's line in the list, which carries how long the action usually takes. */
  usual?: TraceSummary;
  /** Set when this trace is a replay: the original's ID and a readable label. */
  replayOf?: { traceId: string; label: string };
  onOpenTrace?: (traceId: string) => void;
}) {
  const total = Math.max(trace.durationNanos, 1);
  const [selectedId, setSelectedId] = useState<string>();
  const [placement, setPlacement] = useDetailPlacement();
  const [editor, setEditor] = useEditor();
  const [comparing, setComparing] = useState(false);
  // Log lines are point events: listed under their span and in the Logs panel, not as waterfall rows.
  const rows = trace.spans.filter((s) => s.kind !== 'LOG');
  const logs = trace.spans.filter((s) => s.kind === 'LOG');
  const logsBySpan = new Map<string, SpanRow[]>();
  for (const log of logs) {
    const key = log.parentSpanId ?? '';
    logsBySpan.set(key, [...(logsBySpan.get(key) ?? []), log]);
  }
  const selectedIndex = rows.findIndex((s) => s.spanId === selectedId);
  const selected = selectedIndex >= 0 ? rows[selectedIndex] : undefined;
  const close = useCallback(() => setSelectedId(undefined), []);
  const step = useCallback(
    (delta: number) => {
      const next = rows[Math.min(rows.length - 1, Math.max(0, selectedIndex + delta))];
      if (next) {
        setSelectedId(next.spanId);
        document.querySelector(`[data-span-row="${next.spanId}"]`)?.scrollIntoView({ block: 'nearest' });
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps -- rows is derived from trace.spans
    [trace.spans, selectedIndex],
  );

  // Esc closes the details; the arrow keys walk the trace while they are open.
  useEffect(() => {
    if (!selected) {
      return;
    }
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT')) {
        return;
      }
      if (e.key === 'Escape') {
        close();
      } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        step(e.key === 'ArrowDown' ? 1 : -1);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [selected, close, step]);
  const flagged = new Map(trace.insights.map((i) => [i.spanId, i]));
  const exceptions = trace.spans.filter((s) => s.kind === 'EXCEPTION');
  const factor = usual ? slowdown(usual) : undefined;
  // The service the trace was recorded in; spans from other services get a label.
  const home = trace.spans.find((s) => s.source !== 'browser')?.source;

  const side = selected !== undefined && placement === 'side';
  const detail = selected && (
    <SpanDetail
      span={selected}
      placement={placement}
      onPlacement={setPlacement}
      onClose={close}
      onStep={step}
      position={{ index: selectedIndex, total: rows.length }}
      token={token}
      editor={editor}
      onEditor={setEditor}
      logs={logsBySpan.get(selected.spanId) ?? []}
    />
  );

  return (
    <div className={side ? 'xl:flex xl:items-start' : ''}>
    <section aria-label={`Timeline for ${trace.name}`} className="min-w-0 flex-1 pb-10">
      <header className="border-b border-line px-6 pt-6 pb-5">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <span className="eyebrow">
            {formatClock(trace.startTimeUnixNano)} · trace {trace.traceId.slice(0, 8)}…{trace.traceId.slice(-4)}
          </span>
          <code className="sr-only">trace {trace.traceId}</code>
          <div className="ml-auto flex items-center gap-2">
            {traces.some((t) => t.traceId !== trace.traceId) && (
              <button
                type="button"
                aria-expanded={comparing}
                onClick={() => setComparing((c) => !c)}
                title="Compare this run with another recorded trace, span by span"
                className="btn"
              >
                Compare with…
              </button>
            )}
            <button
              type="button"
              onClick={() => void downloadReport(trace.traceId, token).catch(() => {})}
              title="Save a single HTML file with this trace's timeline, insights and exceptions, secrets redacted. Opens in any browser."
              className="btn"
            >
              Bug report
            </button>
            <button
              type="button"
              onClick={() => void downloadTrace(trace.traceId, token).catch(() => {})}
              title="Save this trace as a JSON file, e.g. to attach to a bug report. It contains the redacted spans shown here."
              className="btn"
            >
              Export
            </button>
          </div>
        </div>
        <h2 className="fade-up mt-2 font-serif text-4xl leading-tight tracking-tight md:text-[44px]">{trace.name}</h2>
        <div className="mt-3 flex flex-wrap items-center gap-2 text-[13px]">
          <span className="chip font-mono text-ink">{formatDuration(trace.durationNanos)}</span>
          <span className="chip text-ink-2">{rows.length} spans</span>
          {logs.length > 0 && (
            <a href="#trace-logs" className="chip text-ink-2 hover:border-ink/50">
              {logs.length} log {logs.length === 1 ? 'line' : 'lines'}
            </a>
          )}
          {trace.status === 'ERROR' ? (
            <span className="chip border-error/50 bg-error/10 font-medium text-error">Failed</span>
          ) : (
            <span className="chip border-ok/40 text-ok">OK</span>
          )}
          {factor !== undefined && usual?.baselineNanos && (
            <span
              className={`chip ${factor >= SLOW_FACTOR ? 'border-warn/50 bg-warn/10 text-warn' : 'text-muted'}`}
              title={`Usual duration: the median of ${usual.baselineRuns ?? 0} other successful runs of ${trace.name}`}
            >
              {factor >= SLOW_FACTOR
                ? `${factor.toFixed(1)}× slower than usual (${formatDuration(usual.baselineNanos)})`
                : `usual ${formatDuration(usual.baselineNanos)}`}
            </span>
          )}
          {replayOf && (
            <button
              type="button"
              onClick={() => onOpenTrace?.(replayOf.traceId)}
              className="chip border-server/40 text-server hover:bg-server/10"
            >
              Replay of {replayOf.label}
            </button>
          )}
        </div>
      </header>

      {comparing && (
        <ComparePanel key={trace.traceId} trace={trace} traces={traces} token={token} onOpenTrace={onOpenTrace} />
      )}

      <ReplayPanel
        key={trace.traceId}
        traceId={trace.traceId}
        token={token}
        spans={trace.spans}
        capturedBodies={Object.fromEntries(
          trace.spans
            .filter((s) => s.attributes['http.request.body'] !== undefined && s.source !== 'browser')
            .map((s) => [s.spanId, s.attributes['http.request.body'] ?? '']),
        )}
      />

      {trace.insights.length > 0 && (
        <ul aria-label="Insights" className="grid gap-2 px-6 pt-5 lg:grid-cols-2">
          {trace.insights.map((insight, i) => (
            <li key={insight.rule + insight.spanId} className="fade-up" style={{ animationDelay: `${i * 60}ms` }}>
              <button
                type="button"
                onClick={() => setSelectedId(insight.spanId)}
                className="panel group relative flex w-full items-start gap-3 overflow-hidden p-3 pl-4 text-left text-sm transition-colors hover:border-ink/40"
              >
                <span aria-hidden className={`absolute top-0 bottom-0 left-0 w-1 ${INSIGHT_STYLE[insight.rule].accent}`} />
                <span className="eyebrow shrink-0 pt-0.5">{INSIGHT_STYLE[insight.rule].tag}</span>
                <span className="text-ink-2 group-hover:text-ink">{insight.label}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="panel mx-6 mt-5 overflow-hidden">
        <div className="flex flex-wrap items-center gap-4 border-b border-line px-4 py-2.5 text-xs text-muted">
          <span className="font-medium text-ink-2">Waterfall</span>
          <Legend color="bg-browser" label="Browser" />
          <Legend color="bg-server" label="Server" />
          <Legend color="bg-signal" label="Bottleneck" />
          <Legend color="bg-error" label="Failed" />
        </div>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[640px] table-fixed text-sm">
            <thead>
              <tr className="text-left text-[11px] text-muted">
                <th className="w-[42%] px-4 py-2 font-medium">Operation</th>
                <th className="px-2 py-2 font-medium">
                  <span className="sr-only">Timeline</span>
                  <div aria-hidden className="relative h-4 font-mono">
                    {TICKS.map((t) => (
                      <span
                        key={t}
                        className="absolute top-0 whitespace-nowrap"
                        style={{ left: `${t * 100}%`, transform: t === 0 ? 'none' : t === 1 ? 'translateX(-100%)' : 'translateX(-50%)' }}
                      >
                        {formatDuration(total * t)}
                      </span>
                    ))}
                  </div>
                </th>
                <th className="w-20 px-2 py-2 text-right font-medium">Duration</th>
                <th className="w-28 px-4 py-2 text-right font-medium">Self</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((span, index) => {
                const left = (span.offsetNanos / total) * 100;
                const width = Math.max((span.durationNanos / total) * 100, 0.4);
                const insight = flagged.get(span.spanId);
                const bottleneck = insight?.rule === 'PRIMARY_BOTTLENECK';
                const active = span.spanId === selectedId;
                return (
                  <tr
                    key={span.spanId}
                    onClick={() => setSelectedId(active ? undefined : span.spanId)}
                    aria-selected={active}
                    data-span-row={span.spanId}
                    className={`group cursor-pointer border-t border-line-soft transition-colors hover:bg-paper-2 ${
                      active ? 'bg-raised' : ''
                    }`}
                  >
                    <td className="relative px-4 py-1.5">
                      {active && <span aria-hidden className="absolute top-1 bottom-1 left-0 w-0.5 rounded-full bg-signal" />}
                      <div className="flex items-center gap-2" style={{ paddingLeft: span.depth * 14 }}>
                        <span className={`w-[72px] shrink-0 font-mono text-[10px] tracking-wide uppercase ${kindColor(span)}`}>
                          {KIND_LABEL[span.kind]}
                        </span>
                        <span className={`truncate ${span.status === 'ERROR' ? 'text-error' : 'text-ink'}`} title={span.name}>
                          {span.name}
                        </span>
                        {span.source !== 'browser' && home && span.source !== home && (
                          <span className="chip shrink-0 border-server/40 px-1.5 text-[10px] text-server" title="Recorded by another service">
                            {span.source}
                          </span>
                        )}
                        <SpanChips span={span} logCount={logsBySpan.get(span.spanId)?.length ?? 0} />
                        {isHandled(span) && (
                          <span
                            className="chip shrink-0 px-1.5 text-[10px] text-muted"
                            title="Caught and logged by the application; the request continued"
                          >
                            handled
                          </span>
                        )}
                        {bottleneck && (
                          <span className="shrink-0 rounded-md bg-signal px-1.5 font-mono text-[10px] font-semibold tracking-wide text-white uppercase">
                            bottleneck
                          </span>
                        )}
                        {span.attributes['causeline.inferred_cause'] && (
                          <span
                            className="shrink-0 truncate text-[10px] text-muted"
                            title="Inferred from timing: this request followed the click within a second. It is not linked to it."
                          >
                            after {span.attributes['causeline.inferred_cause']}?
                          </span>
                        )}
                        {isInferred(span) && (
                          <span className="shrink-0 text-[10px] text-muted" title="Linked by timing, not by an explicit trace() call">
                            inferred
                          </span>
                        )}
                        {span.clockSkew && (
                          <span className="shrink-0 text-[10px] text-warn" title="Placed at the parent's start: this span was longer than its parent">
                            skew
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="px-2 py-1.5">
                      <div className="relative h-3">
                        <div aria-hidden className="absolute inset-y-[5px] right-0 left-0 rounded-full bg-line-soft" />
                        <div
                          className={`grow-x absolute top-0 h-3 rounded-[4px] ${barColor(span, bottleneck)} ${
                            isInferred(span) ? 'opacity-60' : ''
                          } ${bottleneck ? 'ring-2 ring-signal/30' : ''}`}
                          style={{
                            left: `${left}%`,
                            width: `${Math.min(width, 100 - left)}%`,
                            animationDelay: `${Math.min(index * 18, 400)}ms`,
                          }}
                          title={`${span.name}: starts at ${formatDuration(span.offsetNanos)}`}
                        />
                      </div>
                    </td>
                    <td className="px-2 py-1.5 text-right font-mono text-[12px] whitespace-nowrap text-ink-2 tabular-nums">
                      {formatDuration(span.durationNanos)}
                    </td>
                    <td className="px-4 py-1.5 text-right font-mono text-[12px] whitespace-nowrap text-muted tabular-nums">
                      {formatDuration(span.selfNanos)}
                      <span className="ml-1 text-[10px]">({percent(span.selfNanos, total)}%)</span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      {selected && placement === 'below' && <div className="panel fade-up mx-6 mt-5">{detail}</div>}
      {exceptions.some((e) => !isHandled(e)) && (
        <ExceptionPanel
          exceptions={exceptions.filter((e) => !isHandled(e))}
          handled={false}
          token={token}
          editor={editor}
        />
      )}
      {exceptions.some(isHandled) && (
        <ExceptionPanel exceptions={exceptions.filter(isHandled)} handled token={token} editor={editor} />
      )}
      {logs.length > 0 && (
        <LogPanel logs={logs} spans={trace.spans} onSelect={(spanId) => setSelectedId(spanId)} />
      )}
    </section>
    {side && (
      <>
        {/* Below xl the panel slides over the page; the backdrop closes it. */}
        <div aria-hidden className="fixed inset-0 z-40 bg-black/45 backdrop-blur-[2px] xl:hidden" onClick={close} />
        <aside
          aria-label="Span details panel"
          className="slide-in fixed inset-y-0 right-0 z-50 w-[min(92vw,460px)] overflow-y-auto border-l border-line bg-paper shadow-2xl xl:sticky xl:top-14 xl:z-auto xl:h-[calc(100vh-3.5rem)] xl:w-[420px] xl:shrink-0 xl:shadow-none"
        >
          {detail}
        </aside>
      </>
    )}
    </div>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className={`inline-block h-2 w-3 rounded-sm ${color}`} /> {label}
    </span>
  );
}

/** Long, multi-line values shown in their own block below the attribute list. */
const BLOCK_ATTRIBUTES = new Set([
  'exception.stacktrace',
  'http.request.body',
  'http.response.body',
  'db.query.text',
  'db.query.statement',
  'causeline.arguments',
  'causeline.return',
]);

function prettyBody(value: string): string {
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

export function SpanDetail({
  span,
  onClose,
  placement = 'below',
  onPlacement,
  onStep,
  position,
  token,
  editor = 'vscode',
  onEditor,
  logs = [],
}: {
  span: SpanRow;
  onClose?: () => void;
  placement?: DetailPlacement;
  onPlacement?: (placement: DetailPlacement) => void;
  onStep?: (delta: number) => void;
  position?: { index: number; total: number };
  token?: string;
  editor?: Editor;
  onEditor?: (editor: Editor) => void;
  /** Log lines written while this span was the active one. */
  logs?: SpanRow[];
}) {
  const narrow = placement === 'side';
  const entries = Object.entries(span.attributes).filter(([key]) => !BLOCK_ATTRIBUTES.has(key));
  const body = span.attributes['http.request.body'];
  const responseBody = span.attributes['http.response.body'];
  const sql = span.attributes['db.query.text'];
  const prepared = span.attributes['db.query.statement'];
  const args = span.attributes['causeline.arguments'];
  const returned = span.attributes['causeline.return'];
  return (
    <section aria-label="Span detail" className="p-5 text-sm">
      {(onClose || onPlacement || onStep) && (
        <div className="-mx-5 -mt-5 mb-4 flex items-center gap-1.5 border-b border-line px-4 py-2.5">
          {onStep && (
            <>
              <IconButton label="Previous span (↑)" onClick={() => onStep(-1)} disabled={position?.index === 0}>
                <path d="m6 15 6-6 6 6" />
              </IconButton>
              <IconButton
                label="Next span (↓)"
                onClick={() => onStep(1)}
                disabled={position !== undefined && position.index >= position.total - 1}
              >
                <path d="m6 9 6 6 6-6" />
              </IconButton>
              {position && (
                <span className="ml-1 font-mono text-[11px] text-muted">
                  {position.index + 1} / {position.total}
                </span>
              )}
            </>
          )}
          <div className="ml-auto flex items-center gap-1.5">
            {onPlacement && (
              <div role="group" aria-label="Show details" className="flex rounded-full border border-line p-0.5">
                <PlacementButton current={placement} value="side" onChoose={onPlacement} label="On the side">
                  <rect x="3" y="4" width="18" height="16" rx="2" />
                  <path d="M14 4v16" />
                </PlacementButton>
                <PlacementButton current={placement} value="below" onChoose={onPlacement} label="Below the timeline">
                  <rect x="3" y="4" width="18" height="16" rx="2" />
                  <path d="M3 14h18" />
                </PlacementButton>
              </div>
            )}
            {onClose && (
              <IconButton label="Close details (Esc)" onClick={onClose}>
                <path d="M6 6l12 12M18 6 6 18" />
              </IconButton>
            )}
          </div>
        </div>
      )}
      <div className="min-w-0">
        <p className={`eyebrow ${kindColor(span)}`}>
          {KIND_LABEL[span.kind]} · {span.source}
        </p>
        <h3 className={`mt-1 font-serif leading-tight break-words ${narrow ? 'text-xl' : 'text-2xl'}`}>
          {KIND_LABEL[span.kind]}: {span.name}
        </h3>
      </div>
      <SpanActions span={span} token={token} editor={editor} onEditor={onEditor} />
      <div className={`mt-4 grid grid-cols-2 gap-2 ${narrow ? '' : 'sm:grid-cols-4'}`}>
        <Stat label="Status" value={span.status} tone={span.status === 'ERROR' ? 'text-error' : 'text-ink'} />
        <Stat label="Starts at" value={formatDuration(span.offsetNanos)} />
        <Stat label="Duration" value={formatDuration(span.durationNanos)} />
        <Stat label="Self" value={formatDuration(span.selfNanos)} />
      </div>
      {entries.length > 0 && (
        <dl
          className={`mt-4 grid gap-x-4 gap-y-1.5 border-t border-line-soft pt-4 text-xs ${
            narrow ? 'grid-cols-1 [&>dd]:mb-1.5' : 'grid-cols-[minmax(8rem,12rem)_1fr]'
          }`}
        >
          <dt className="sr-only">Source</dt>
          <dd className="sr-only">{span.source}</dd>
          {entries.map(([key, value]) => (
            <Attribute key={key} name={key} value={value} />
          ))}
        </dl>
      )}
      {logs.length > 0 && (
        <div className="mt-4">
          <span className="eyebrow">Logged here ({logs.length})</span>
          <ul className="mt-1.5 space-y-1">
            {logs.map((log) => (
              <LogLine key={log.spanId} log={log} />
            ))}
          </ul>
        </div>
      )}
      {args && (
        <Block
          title={span.attributes['causeline.replay.edited'] === 'true' ? 'Arguments (edited during replay)' : 'Arguments'}
          value={prettyBody(args)}
        />
      )}
      {returned && <Block title="Returned" value={prettyBody(returned)} />}
      {sql && <Block title={prepared ? 'SQL (with values)' : 'SQL'} value={sql} />}
      {prepared && <Block title="SQL as prepared" value={prepared} />}
      {body && <Block title="Request body" value={prettyBody(body)} />}
      {responseBody && <Block title="Response body" value={prettyBody(responseBody)} />}
    </section>
  );
}

function IconButton({
  label,
  onClick,
  disabled,
  children,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      title={label}
      className="grid h-7 w-7 place-items-center rounded-full border border-line text-ink-2 transition-colors hover:border-ink hover:text-ink disabled:cursor-default disabled:opacity-30 disabled:hover:border-line"
    >
      <svg aria-hidden width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        {children}
      </svg>
    </button>
  );
}

function PlacementButton({
  current,
  value,
  onChoose,
  label,
  children,
}: {
  current: DetailPlacement;
  value: DetailPlacement;
  onChoose: (placement: DetailPlacement) => void;
  label: string;
  children: ReactNode;
}) {
  const active = current === value;
  return (
    <button
      type="button"
      aria-pressed={active}
      aria-label={label}
      title={label}
      onClick={() => onChoose(value)}
      className={`grid h-6 w-7 place-items-center rounded-full transition-colors ${
        active ? 'bg-ink text-paper' : 'text-muted hover:text-ink'
      }`}
    >
      <svg aria-hidden width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        {children}
      </svg>
    </button>
  );
}

function Stat({ label, value, tone = 'text-ink' }: { label: string; value: string; tone?: string }) {
  return (
    <div className="rounded-lg border border-line-soft bg-paper-2 px-3 py-2">
      <div className="text-[10px] tracking-wide text-muted uppercase">{label}</div>
      <div className={`mt-0.5 font-mono text-[13px] ${tone}`}>{value}</div>
    </div>
  );
}

function Block({ title, value }: { title: string; value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="mt-4">
      <div className="flex items-center justify-between">
        <span className="eyebrow">{title}</span>
        <button
          type="button"
          onClick={() =>
            void navigator.clipboard?.writeText(value).then(() => {
              setCopied(true);
              setTimeout(() => setCopied(false), 1400);
            })
          }
          className="font-mono text-[10px] tracking-wider text-muted uppercase hover:text-ink"
        >
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre className="mt-1.5 max-h-72 overflow-auto rounded-lg border border-line-soft bg-paper-2 p-3 text-[11.5px] leading-relaxed break-all whitespace-pre-wrap text-ink-2">
        {value}
      </pre>
    </div>
  );
}

function Attribute({ name, value }: { name: string; value: string }) {
  return (
    <>
      <dt className="truncate font-mono text-[11px] text-muted" title={name}>
        {name}
      </dt>
      <dd className="break-all text-ink-2">{value}</dd>
    </>
  );
}

function ExceptionPanel({
  exceptions,
  handled,
  token,
  editor = 'vscode',
}: {
  exceptions: SpanRow[];
  handled: boolean;
  token?: string;
  editor?: Editor;
}) {
  const title = handled
    ? `${exceptions.length === 1 ? 'Handled exception' : `${exceptions.length} handled exceptions`}: caught and logged, request continued`
    : exceptions.length === 1
      ? 'Exception'
      : `${exceptions.length} exceptions`;
  return (
    <section
      aria-label={handled ? 'Handled exceptions' : 'Exceptions'}
      className={`panel mx-6 mt-5 overflow-hidden text-sm ${handled ? '' : 'border-error/40'}`}
    >
      <div className={`flex items-center gap-2 border-b px-5 py-3 ${handled ? 'border-line' : 'border-error/30 bg-error/10'}`}>
        <span aria-hidden className={`h-2 w-2 rounded-full ${handled ? 'bg-muted' : 'bg-error'}`} />
        <h3 className={`font-semibold ${handled ? 'text-ink-2' : 'text-error'}`}>{title}</h3>
      </div>
      <ul className="divide-y divide-line-soft">
        {exceptions.map((e) => (
          <li key={e.spanId} className="px-5 py-4">
            <div className="font-mono text-[13px] font-medium text-ink">{e.attributes['exception.type'] ?? e.name}</div>
            {e.attributes['code.location'] && (
              <div className="mt-0.5 text-xs text-muted">
                at {e.attributes['code.function']} (<span className="font-mono text-ink-2">{e.attributes['code.location']}</span>)
                <OpenSource span={e} token={token} editor={editor} compact />
              </div>
            )}
            {handled && e.attributes['log.logger'] && (
              <div className="text-xs text-muted">
                logged at {e.attributes['log.level']} by {e.attributes['log.logger']}
              </div>
            )}
            {e.attributes['log.message'] && <div className="mt-2 text-xs text-ink-2">Log: {e.attributes['log.message']}</div>}
            {e.attributes['exception.message'] && <div className="mt-2 text-xs text-ink-2">{e.attributes['exception.message']}</div>}
            {e.attributes['exception.stacktrace'] ? (
              <details className="mt-2 text-xs">
                <summary className="cursor-pointer text-muted hover:text-ink">Stack trace</summary>
                <pre className="mt-2 max-h-72 overflow-auto rounded-lg border border-line-soft bg-paper-2 p-3 text-[11px] whitespace-pre text-ink-2">
                  {e.attributes['exception.stacktrace']}
                </pre>
              </details>
            ) : (
              <div className="mt-2 text-xs text-muted">
                Message and stack trace are hidden. Set <code className="text-ink-2">causeline.capture.exception-details=true</code> to
                record them.
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Small markers on a waterfall row: cache hit or miss, a rolled-back transaction, edited arguments, logs. */
function SpanChips({ span, logCount }: { span: SpanRow; logCount: number }) {
  const a = span.attributes;
  return (
    <>
      {a['cache.hit'] === 'true' && <span className="chip shrink-0 border-ok/40 px-1.5 text-[10px] text-ok">hit</span>}
      {a['cache.hit'] === 'false' && <span className="chip shrink-0 border-warn/40 px-1.5 text-[10px] text-warn">miss</span>}
      {a['db.transaction.outcome'] && a['db.transaction.outcome'] !== 'commit' && (
        <span className="chip shrink-0 border-error/40 px-1.5 text-[10px] text-error" title="The transaction did not commit">
          {a['db.transaction.outcome']}
        </span>
      )}
      {a['causeline.replay.edited'] === 'true' && (
        <span className="chip shrink-0 border-server/50 px-1.5 text-[10px] text-server" title="Run with arguments changed during a paused replay">
          edited
        </span>
      )}
      {logCount > 0 && (
        <span className="shrink-0 font-mono text-[10px] text-muted" title="Log lines written during this span">
          {logCount} log{logCount === 1 ? '' : 's'}
        </span>
      )}
    </>
  );
}

const LEVEL_TONE: Record<string, string> = { ERROR: 'text-error', WARN: 'text-warn', INFO: 'text-ink-2', DEBUG: 'text-muted' };

function LogLine({ log, owner, onSelect }: { log: SpanRow; owner?: SpanRow; onSelect?: () => void }) {
  const level = log.attributes['log.level'] ?? 'INFO';
  const logger = log.attributes['log.logger'] ?? '';
  return (
    <li className="grid grid-cols-[auto_auto_1fr] items-baseline gap-x-2 font-mono text-[11.5px]">
      <span className="text-muted tabular-nums">{formatDuration(log.offsetNanos)}</span>
      <span className={`w-11 ${LEVEL_TONE[level] ?? 'text-ink-2'}`}>{level}</span>
      <span className="min-w-0 break-words text-ink-2">
        <span className="text-muted" title={logger}>
          {logger.slice(logger.lastIndexOf('.') + 1)}
        </span>{' '}
        {log.attributes['log.message'] ?? log.name}
        {owner && (
          <button type="button" onClick={onSelect} className="ml-2 text-[10px] text-muted underline-offset-2 hover:text-ink hover:underline">
            in {owner.name}
          </button>
        )}
      </span>
    </li>
  );
}

/** Every log line in the trace, in time order, each linked to the span it was written in. */
function LogPanel({ logs, spans, onSelect }: { logs: SpanRow[]; spans: SpanRow[]; onSelect: (spanId: string) => void }) {
  const byId = new Map(spans.map((s) => [s.spanId, s]));
  const sorted = [...logs].sort((a, b) => a.offsetNanos - b.offsetNanos);
  return (
    <section id="trace-logs" aria-label="Logs" className="panel mx-6 mt-5 overflow-hidden text-sm">
      <div className="flex items-center gap-2 border-b border-line px-5 py-3">
        <h3 className="font-semibold text-ink-2">Logs</h3>
        <span className="text-xs text-muted">
          {logs.length} {logs.length === 1 ? 'line' : 'lines'} written during this trace
        </span>
      </div>
      <ul className="max-h-80 space-y-1 overflow-y-auto px-5 py-3">
        {sorted.map((log) => {
          const owner = log.parentSpanId ? byId.get(log.parentSpanId) : undefined;
          return (
            <LogLine key={log.spanId} log={log} owner={owner} onSelect={owner ? () => onSelect(owner.spanId) : undefined} />
          );
        })}
      </ul>
    </section>
  );
}

/** Opens the span's code in the developer's editor: the method, or the line an exception was thrown at. */
function OpenSource({
  span,
  token,
  editor,
  compact = false,
}: {
  span: SpanRow;
  token?: string;
  editor: Editor;
  compact?: boolean;
}) {
  const [missing, setMissing] = useState(false);
  const a = span.attributes;
  const className = a['code.namespace'];
  const filePath = a['code.filepath'];
  if (!className && !filePath) {
    return null;
  }
  const line = Number(a['code.lineno']) || 0;
  const fn = a['code.function'] ?? '';
  const method = fn.includes('.') ? fn.slice(fn.lastIndexOf('.') + 1) : fn;
  const open = async () => {
    if (filePath) {
      openInEditor(editor, filePath, line || 1);
      return;
    }
    const location = await fetchSource({ className: className ?? '', method, line }, token).catch(() => undefined);
    if (location) {
      openInEditor(editor, location.path, location.line);
    } else {
      setMissing(true);
    }
  };
  const label = EDITORS.find((e) => e.id === editor)?.label ?? 'editor';
  return (
    <>
      <button
        type="button"
        onClick={() => void open()}
        title={`Open ${filePath ?? className}${line ? `:${line}` : method ? ` at ${method}` : ''} in ${label}`}
        className={compact ? 'ml-2 text-[11px] text-server underline-offset-2 hover:underline' : 'btn'}
      >
        {compact ? 'Open' : `Open in ${label}`}
      </button>
      {missing && <span className="ml-2 text-[11px] text-muted">Source not found under the app's working directory.</span>}
    </>
  );
}

/** Things to do with a span: open its code, copy its request as a command or a test. */
function SpanActions({
  span,
  token,
  editor,
  onEditor,
}: {
  span: SpanRow;
  token?: string;
  editor: Editor;
  onEditor?: (editor: Editor) => void;
}) {
  const request = capturedRequest(span);
  const [copied, setCopied] = useState<string>();
  const hasSource = Boolean(span.attributes['code.namespace'] || span.attributes['code.filepath']);
  if (!request && !hasSource) {
    return null;
  }
  const copy = (what: string, text: string) =>
    void navigator.clipboard?.writeText(text).then(() => {
      setCopied(what);
      setTimeout(() => setCopied(undefined), 1400);
    });
  return (
    <div className="mt-3 flex flex-wrap items-center gap-2">
      {hasSource && (
        <>
          <OpenSource span={span} token={token} editor={editor} />
          {onEditor && (
            <select
              aria-label="Editor"
              value={editor}
              onChange={(e) => onEditor(e.target.value as Editor)}
              className="rounded-full border border-line bg-paper-2 px-2 py-1 text-[11px] text-ink-2"
            >
              {EDITORS.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.label}
                </option>
              ))}
            </select>
          )}
        </>
      )}
      {request && (
        <>
          <button type="button" className="btn" onClick={() => copy('curl', toCurl(request, window.location.origin))}>
            {copied === 'curl' ? 'Copied' : 'Copy as cURL'}
          </button>
          {span.source !== 'browser' && (
            <>
              <button
                type="button"
                className="btn"
                title="A JUnit test that sends this request with MockMvc and expects the same status (Spring MVC)"
                onClick={() => copy('mockmvc', toMockMvcTest(request))}
              >
                {copied === 'mockmvc' ? 'Copied' : 'Copy as MockMvc test'}
              </button>
              <button
                type="button"
                className="btn"
                title="The same test with WebTestClient (WebFlux)"
                onClick={() => copy('webtestclient', toWebTestClientTest(request))}
              >
                {copied === 'webtestclient' ? 'Copied' : 'Copy as WebTestClient test'}
              </button>
            </>
          )}
        </>
      )}
    </div>
  );
}

/** This run next to another recorded one, span by span: what got slower, what failed, what's new. */
function ComparePanel({
  trace,
  traces,
  token,
  onOpenTrace,
}: {
  trace: TraceView;
  traces: TraceSummary[];
  token?: string;
  onOpenTrace?: (traceId: string) => void;
}) {
  // Runs of the same action first: that is almost always what you want to compare with.
  const others = traces
    .filter((t) => t.traceId !== trace.traceId)
    .sort((a, b) => Number(b.name === trace.name) - Number(a.name === trace.name) || b.startTimeUnixNano - a.startTimeUnixNano);
  const [other, setOther] = useState(others[0]?.traceId ?? '');
  const [result, setResult] = useState<ComparisonResult>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    if (!other) {
      return undefined;
    }
    let cancelled = false;
    setError(undefined);
    compareTraces(other, trace.traceId, token)
      .then((r) => !cancelled && setResult(r))
      .catch((e: unknown) => !cancelled && setError(e instanceof Error ? e.message : String(e)));
    return () => {
      cancelled = true;
    };
  }, [other, trace.traceId, token]);
  const chosen = others.find((t) => t.traceId === other);
  return (
    <section aria-label="Compare traces" className="panel fade-up mx-6 mt-5 space-y-3 p-4 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="font-semibold">Compare with</h3>
        <select
          aria-label="Trace to compare with"
          value={other}
          onChange={(e) => setOther(e.target.value)}
          className="min-w-0 max-w-full rounded-lg border border-line bg-paper-2 px-2 py-1 text-[13px] text-ink"
        >
          {others.map((t) => (
            <option key={t.traceId} value={t.traceId}>
              {t.name} at {formatClock(t.startTimeUnixNano)} · {formatDuration(t.durationNanos)}
              {t.status === 'ERROR' ? ' · failed' : ''}
            </option>
          ))}
        </select>
        {chosen && onOpenTrace && (
          <button type="button" onClick={() => onOpenTrace(chosen.traceId)} className="text-xs text-muted hover:text-ink">
            Open it
          </button>
        )}
      </div>
      {error && <p role="alert" className="text-xs text-error">{error}</p>}
      {result && (
        <div className="rounded-lg border border-line-soft bg-paper-2 p-3">
          <ComparisonTable
            result={result}
            left={chosen ? `Then (${formatClock(chosen.startTimeUnixNano)})` : 'Then'}
            right="This run"
          />
        </div>
      )}
    </section>
  );
}
