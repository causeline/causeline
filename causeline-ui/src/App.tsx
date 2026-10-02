// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef, useState } from 'react';
import { UnauthorizedError, fetchStatus, fetchTrace, fetchTraces, importTrace } from './api';
import type { Status, TraceSummary, TraceView } from './api';
import { DropWarning, StatusLine } from './StatusBar';
import { Timeline } from './Timeline';
import { TraceList, originalLabel } from './TraceList';
import { readToken } from './token';

const POLL_MS = 2000;

type Problem = { kind: 'unauthorized' } | { kind: 'unreachable'; message: string };

export function App() {
  const [token] = useState(() => (typeof window === 'undefined' ? undefined : readToken()));
  const [traces, setTraces] = useState<TraceSummary[]>([]);
  const [selected, setSelected] = useState<string>();
  const [trace, setTrace] = useState<TraceView>();
  const [status, setStatus] = useState<Status>();
  const [problem, setProblem] = useState<Problem>();
  const [importMessage, setImportMessage] = useState<string>();
  const fileInput = useRef<HTMLInputElement>(null);
  const replayOfSelected = traces.find((t) => t.traceId === selected)?.replayOf ?? undefined;

  // The list refreshes on a timer; the open trace too, because browser spans arrive after server spans.
  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const [list, currentStatus] = await Promise.all([fetchTraces(token), fetchStatus(token)]);
        const open = selected ? await fetchTrace(selected, token) : undefined;
        if (!cancelled) {
          setTraces(list);
          setStatus(currentStatus);
          setTrace(open);
          setProblem(undefined);
        }
      } catch (e) {
        if (!cancelled) {
          setProblem(
            e instanceof UnauthorizedError
              ? { kind: 'unauthorized' }
              : { kind: 'unreachable', message: e instanceof Error ? e.message : String(e) },
          );
        }
      }
    };
    void load();
    const timer = setInterval(() => void load(), POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [selected, token]);

  const onImport = async (file: File | undefined) => {
    if (!file) {
      return;
    }
    try {
      const traceId = await importTrace(await file.text(), token);
      setImportMessage(undefined);
      setSelected(traceId);
    } catch (e) {
      setImportMessage(e instanceof Error ? e.message : String(e));
    } finally {
      if (fileInput.current) {
        fileInput.current.value = '';
      }
    }
  };

  return (
    <div className="flex min-h-screen flex-col bg-white text-neutral-900 dark:bg-neutral-950 dark:text-neutral-100">
      <header className="flex items-center gap-3 border-b border-neutral-200 px-6 py-3 dark:border-neutral-800">
        <h1 className="text-base font-semibold">Causeline</h1>
        <span className="text-xs text-neutral-500 dark:text-neutral-400">From click to response</span>
        <span
          className="rounded border border-amber-500/40 px-1.5 text-[11px] text-amber-700 dark:text-amber-400"
          title="Captured headers, cookies, bodies and SQL are shown here as sent. Exported traces and OTLP export redact credentials. Block anything with causeline.capture.* settings."
        >
          Shows captured secrets · exports redacted
        </span>
        {problem?.kind === 'unreachable' && (
          <span role="alert" className="text-xs text-red-600">
            Can't reach the Causeline API ({problem.message})
          </span>
        )}
        {problem === undefined && (
          <label className="ml-auto cursor-pointer rounded border border-neutral-300 px-2 py-1 text-xs hover:bg-neutral-100 dark:border-neutral-700 dark:hover:bg-neutral-900">
            Import trace…
            <input
              ref={fileInput}
              type="file"
              accept="application/json,.json"
              className="sr-only"
              onChange={(e) => void onImport(e.target.files?.[0])}
            />
          </label>
        )}
      </header>
      {importMessage && (
        <div role="alert" className="border-b border-red-500/40 bg-red-500/10 px-6 py-2 text-xs text-red-700 dark:text-red-400">
          Import failed: {importMessage}
        </div>
      )}
      {problem?.kind === 'unauthorized' ? (
        <main className="max-w-xl p-6 text-sm">
          <h2 className="mb-2 text-base font-semibold">Access token needed</h2>
          <p className="text-neutral-600 dark:text-neutral-400">
            Open the link that starts with <code>Causeline UI:</code> in your application's log. It contains a token
            that changes every time the app restarts, unless you set <code>causeline.access-token</code>.
          </p>
        </main>
      ) : (
        <>
          <DropWarning status={status} />
          <div className="flex min-h-0 flex-1 flex-col md:flex-row">
            <nav
              aria-label="Traces"
              className="flex flex-col border-b border-neutral-200 md:w-80 md:shrink-0 md:border-r md:border-b-0 dark:border-neutral-800"
            >
              <div className="flex-1 md:overflow-y-auto">
                <TraceList traces={traces} selected={selected} onSelect={setSelected} />
              </div>
              <StatusLine status={status} />
            </nav>
            <main className="min-w-0 flex-1 overflow-x-auto">
              {trace ? (
                <Timeline
                  trace={trace}
                  token={token}
                  replayOf={
                    replayOfSelected
                      ? { traceId: replayOfSelected, label: originalLabel(traces, replayOfSelected) }
                      : undefined
                  }
                  onOpenTrace={setSelected}
                />
              ) : (
                <p className="p-6 text-sm text-neutral-500 dark:text-neutral-400">Select a trace to see its timeline.</p>
              )}
            </main>
          </div>
        </>
      )}
    </div>
  );
}
