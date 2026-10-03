// SPDX-License-Identifier: Apache-2.0
import { useEffect, useMemo, useState } from 'react';
import { searchTraces } from './api';
import type { SearchMatch, TraceSummary } from './api';
import { formatClock, formatDuration } from './format';

interface Props {
  traces: TraceSummary[];
  selected: string | undefined;
  onSelect(traceId: string): void;
  /** For searching inside traces; without it, search matches names and IDs only. */
  token?: string;
}

const SEARCH_DELAY_MS = 250;

/** Matches inside traces (bodies, SQL, headers...), found by the server as the developer types. */
function useDeepSearch(query: string, token: string | undefined): Map<string, SearchMatch[]> {
  const [hits, setHits] = useState<Map<string, SearchMatch[]>>(new Map());
  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setHits(new Map());
      return undefined;
    }
    let cancelled = false;
    const timer = setTimeout(() => {
      searchTraces(q, token)
        .then((found) => !cancelled && setHits(new Map(found.map((h) => [h.traceId, h.matches]))))
        .catch(() => {});
    }, SEARCH_DELAY_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query, token]);
  return hits;
}

const FIELD_LABEL: Record<string, string> = {
  name: 'name',
  'http.request.body': 'request body',
  'http.response.body': 'response body',
  'db.query.text': 'SQL',
  'url.query': 'query',
  'causeline.arguments': 'arguments',
  'causeline.return': 'returned',
  'log.message': 'log',
  'exception.message': 'exception',
};

const fieldLabel = (field: string) =>
  FIELD_LABEL[field] ?? (field.startsWith('http.request.header.') ? `header ${field.slice(20)}` : field);

type Filter = 'all' | 'errors' | 'slow' | 'replays';

const FILTERS: { id: Filter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'errors', label: 'Errors' },
  { id: 'slow', label: 'Slower than usual' },
  { id: 'replays', label: 'Replays' },
];

/** "Checkout at 14:03:27", or a short trace ID once the original has been evicted. */
export function originalLabel(traces: TraceSummary[], traceId: string): string {
  const original = traces.find((t) => t.traceId === traceId);
  return original ? `${original.name} at ${formatClock(original.startTimeUnixNano)}` : `trace ${traceId.slice(0, 8)}…`;
}

/** How much slower than the usual run of the same action, e.g. 2.4; undefined without enough history. */
export function slowdown(trace: TraceSummary): number | undefined {
  return trace.baselineNanos && trace.baselineNanos > 0 ? trace.durationNanos / trace.baselineNanos : undefined;
}

/** Slower than usual is worth flagging from 1.5x: below that, it is mostly noise. */
export const SLOW_FACTOR = 1.5;

export function matches(trace: TraceSummary, query: string, filter: Filter): boolean {
  const q = query.trim().toLowerCase();
  if (q && !trace.name.toLowerCase().includes(q) && !trace.traceId.startsWith(q)) {
    return false;
  }
  switch (filter) {
    case 'errors':
      return trace.status === 'ERROR';
    case 'slow':
      return (slowdown(trace) ?? 0) >= SLOW_FACTOR;
    case 'replays':
      return trace.replayOf !== null;
    default:
      return true;
  }
}

export function TraceList({ traces, selected, onSelect, token }: Props) {
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const hits = useDeepSearch(query, token);
  const visible = useMemo(
    () => traces.filter((t) => matches(t, query, filter) || (hits.has(t.traceId) && matches(t, '', filter))),
    [traces, query, filter, hits],
  );
  const longest = Math.max(1, ...traces.map((t) => t.durationNanos));

  if (traces.length === 0) {
    return (
      <p className="p-4 text-sm text-muted">
        No traces yet. Use your app with Causeline enabled; traces appear here within a second or two.
      </p>
    );
  }
  return (
    <div className="flex flex-col">
      <div className="space-y-2 border-b border-line p-3">
        <label className="relative block">
          <span className="sr-only">Search traces</span>
          <svg
            aria-hidden
            className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-muted"
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
          >
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search names, IDs, bodies, SQL, headers…"
            className="w-full rounded-lg border border-line bg-paper-2 py-2 pr-3 pl-9 text-[13px] text-ink placeholder:text-muted focus:border-ink/50 focus:outline-none"
          />
        </label>
        <div role="group" aria-label="Filter traces" className="flex flex-wrap gap-1.5">
          {FILTERS.map((f) => (
            <button
              key={f.id}
              type="button"
              aria-pressed={filter === f.id}
              onClick={() => setFilter(f.id)}
              className={`chip transition-colors ${
                filter === f.id ? 'border-ink bg-ink text-paper' : 'text-muted hover:border-ink/50 hover:text-ink'
              }`}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      {visible.length === 0 && <p className="p-4 text-[13px] text-muted">No traces match.</p>}
      <ul className="space-y-1 p-2">
        {visible.map((trace) => {
          const active = trace.traceId === selected;
          const factor = slowdown(trace);
          return (
            <li key={trace.traceId}>
              <button
                type="button"
                onClick={() => onSelect(trace.traceId)}
                aria-current={active ? 'true' : undefined}
                className={`group relative w-full rounded-xl border px-3 py-2.5 text-left transition-colors ${
                  active ? 'border-line bg-raised' : 'border-transparent hover:bg-paper-2'
                }`}
              >
                {active && <span aria-hidden className="absolute top-3 bottom-3 left-0 w-0.5 rounded-full bg-signal" />}
                <div className="flex items-center gap-2">
                  <span
                    aria-hidden
                    className={`h-2 w-2 shrink-0 rounded-full ${trace.status === 'ERROR' ? 'bg-error' : 'bg-ok'}`}
                  />
                  <span className="truncate text-[14px] font-medium text-ink">{trace.name}</span>
                  {trace.status === 'ERROR' && <span className="chip border-error/40 text-error">Error</span>}
                  {trace.replayOf && <span className="chip border-server/40 text-server">Replay</span>}
                  {trace.imported && (
                    <span className="chip text-muted" title="Loaded from an exported file; kept until the application restarts">
                      Imported
                    </span>
                  )}
                </div>
                {trace.replayOf && (
                  <div className="mt-0.5 truncate pl-4 text-xs text-server">of {originalLabel(traces, trace.replayOf)}</div>
                )}
                {!matches(trace, query, 'all') &&
                  hits.get(trace.traceId)?.slice(0, 2).map((m, i) => (
                    <div key={i} className="mt-0.5 truncate pl-4 text-[11px] text-ink-2" title={`${m.spanName}: ${m.excerpt}`}>
                      <span className="font-mono text-[10px] text-muted uppercase">{fieldLabel(m.field)}</span> {m.excerpt}
                    </div>
                  ))}
                <div className="mt-1.5 flex items-center gap-3 pl-4 font-mono text-[11px] text-muted">
                  <span>{formatClock(trace.startTimeUnixNano)}</span>
                  <span className={factor !== undefined && factor >= SLOW_FACTOR ? 'text-warn' : ''}>
                    {formatDuration(trace.durationNanos)}
                  </span>
                  <span>{trace.spanCount} spans</span>
                  {factor !== undefined && factor >= SLOW_FACTOR && (
                    <span className="text-warn" title="Compared with earlier runs of the same action">
                      {factor.toFixed(1)}× usual
                    </span>
                  )}
                </div>
                <div aria-hidden className="mt-2 ml-4 h-[3px] overflow-hidden rounded-full bg-line-soft">
                  <div
                    className={`h-full rounded-full ${trace.status === 'ERROR' ? 'bg-error/70' : 'bg-server/60'}`}
                    style={{ width: `${Math.max(2, (trace.durationNanos / longest) * 100)}%` }}
                  />
                </div>
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
