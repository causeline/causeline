// SPDX-License-Identifier: Apache-2.0
import type { TraceSummary } from './api';
import { formatClock, formatDuration } from './format';

interface Props {
  traces: TraceSummary[];
  selected: string | undefined;
  onSelect(traceId: string): void;
}

/** "Checkout at 14:03:27", or a short trace ID once the original has been evicted. */
export function originalLabel(traces: TraceSummary[], traceId: string): string {
  const original = traces.find((t) => t.traceId === traceId);
  return original ? `${original.name} at ${formatClock(original.startTimeUnixNano)}` : `trace ${traceId.slice(0, 8)}…`;
}

export function TraceList({ traces, selected, onSelect }: Props) {
  if (traces.length === 0) {
    return (
      <p className="p-4 text-sm text-neutral-500 dark:text-neutral-400">
        No traces yet. Use your app with Causeline enabled; traces appear here within a second or two.
      </p>
    );
  }
  return (
    <ul className="divide-y divide-neutral-200 dark:divide-neutral-800">
      {traces.map((trace) => {
        const active = trace.traceId === selected;
        return (
          <li key={trace.traceId}>
            <button
              type="button"
              onClick={() => onSelect(trace.traceId)}
              aria-current={active ? 'true' : undefined}
              className={`w-full px-4 py-3 text-left hover:bg-neutral-100 dark:hover:bg-neutral-900 ${
                active ? 'bg-neutral-100 dark:bg-neutral-900' : ''
              }`}
            >
              <div className="flex items-center gap-2">
                {trace.status === 'ERROR' && (
                  <span className="rounded bg-red-600 px-1.5 text-xs font-medium text-white">Error</span>
                )}
                {trace.replayOf && (
                  <span className="rounded bg-violet-600 px-1.5 text-xs font-medium text-white">Replay</span>
                )}
                {trace.imported && (
                  <span
                    className="rounded border border-neutral-400 px-1.5 text-xs text-neutral-600 dark:text-neutral-300"
                    title="Loaded from an exported file; kept until the application restarts"
                  >
                    Imported
                  </span>
                )}
                <span className="truncate font-medium">{trace.name}</span>
              </div>
              {trace.replayOf && (
                <div className="mt-0.5 truncate text-xs text-violet-600 dark:text-violet-400">
                  of {originalLabel(traces, trace.replayOf)}
                </div>
              )}
              <div className="mt-1 flex gap-3 text-xs text-neutral-500 dark:text-neutral-400">
                <span>{formatClock(trace.startTimeUnixNano)}</span>
                <span>{formatDuration(trace.durationNanos)}</span>
                <span>{trace.spanCount} spans</span>
              </div>
            </button>
          </li>
        );
      })}
    </ul>
  );
}
