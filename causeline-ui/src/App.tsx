// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef, useState } from 'react';
import { UnauthorizedError, fetchStatus, fetchTrace, fetchTraces, importTrace } from './api';
import type { Status, TraceSummary, TraceView } from './api';
import { EmptyState } from './EmptyState';
import { DropWarning, StatusLine } from './StatusBar';
import { useTheme } from './theme';
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
  const [loaded, setLoaded] = useState(false);
  const [importMessage, setImportMessage] = useState<string>();
  const [theme, toggleTheme] = useTheme();
  const fileInput = useRef<HTMLInputElement>(null);
  const autoSelected = useRef(false);
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
          setLoaded(true);
          // Open the newest trace the first time there is one, so the page is never a blank frame.
          if (!autoSelected.current && !selected && list.length > 0) {
            autoSelected.current = true;
            setSelected(list[0]?.traceId);
          }
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

  const appName = status?.onboarding?.appName;

  return (
    <div className="grain flex min-h-screen flex-col bg-paper text-ink">
      <header className="sticky top-0 z-40 flex h-14 items-center gap-3 border-b border-line bg-paper/80 px-4 backdrop-blur-md md:px-6">
        <div className="flex items-center gap-2.5">
          <BrandMark />
          <h1 className="text-[15px] font-semibold tracking-tight">Causeline</h1>
        </div>
        {appName && (
          <span className="chip hidden text-ink-2 sm:inline-flex" title="The application Causeline is running in">
            <LiveDot ok={!problem} />
            {appName}
          </span>
        )}
        <span
          className="chip hidden border-warn/40 text-warn lg:inline-flex"
          title="Captured headers, cookies, bodies and SQL are shown here as sent. Exported traces and OTLP export redact credentials. Block anything with causeline.capture.* settings."
        >
          <svg aria-hidden width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="4" y="11" width="16" height="10" rx="2" />
            <path d="M8 11V7a4 4 0 0 1 8 0v4" />
          </svg>
          Shows captured secrets · exports redacted
        </span>
        {problem?.kind === 'unreachable' && (
          <span role="alert" className="chip border-error/40 text-error">
            Can't reach the Causeline API ({problem.message})
          </span>
        )}
        <div className="ml-auto flex items-center gap-2">
          {problem === undefined && (
            <label className="btn">
              <svg aria-hidden width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M12 3v12m0 0-4-4m4 4 4-4M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />
              </svg>
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
          <button
            type="button"
            onClick={toggleTheme}
            className="btn h-8 w-8 justify-center p-0"
            aria-label={theme === 'light' ? 'Switch to dark theme' : 'Switch to light theme'}
            title="Switch theme"
          >
            {theme === 'light' ? <MoonIcon /> : <SunIcon />}
          </button>
        </div>
      </header>

      {importMessage && (
        <div role="alert" className="border-b border-error/40 bg-error/10 px-6 py-2 text-xs text-error">
          Import failed: {importMessage}
        </div>
      )}

      {problem?.kind === 'unauthorized' ? (
        <main className="grid flex-1 place-items-center p-6">
          <div className="panel fade-up max-w-md p-8">
            <p className="eyebrow">401 · locked</p>
            <h2 className="mt-3 font-serif text-4xl leading-none">Access token needed</h2>
            <p className="mt-4 text-sm text-ink-2">
              Open the link that starts with <code className="text-ink">Causeline UI:</code> in your application's log.
              It contains a token that changes every time the app restarts, unless you set{' '}
              <code className="text-ink">causeline.access-token</code>.
            </p>
          </div>
        </main>
      ) : (
        <>
          <DropWarning status={status} />
          {loaded && traces.length === 0 ? (
            <main className="flex-1">
              <EmptyState onboarding={status?.onboarding} />
            </main>
          ) : (
            <div className="flex min-h-0 flex-1 flex-col md:flex-row">
              <nav
                aria-label="Traces"
                className="flex flex-col border-b border-line md:sticky md:top-14 md:h-[calc(100vh-3.5rem)] md:w-[340px] md:shrink-0 md:border-r md:border-b-0"
              >
                <div className="max-h-[45vh] flex-1 overflow-y-auto md:max-h-none">
                  <TraceList traces={traces} selected={selected} onSelect={setSelected} />
                </div>
                <StatusLine status={status} />
              </nav>
              <main className="min-w-0 flex-1">
                {trace ? (
                  <Timeline
                    trace={trace}
                    token={token}
                    usual={traces.find((t) => t.traceId === trace.traceId)}
                    replayOf={
                      replayOfSelected
                        ? { traceId: replayOfSelected, label: originalLabel(traces, replayOfSelected) }
                        : undefined
                    }
                    onOpenTrace={setSelected}
                  />
                ) : (
                  <div className="grid h-full min-h-[50vh] place-items-center p-6 text-center">
                    <div className="fade-up">
                      <p className="font-serif text-4xl text-ink-2">Pick a trace</p>
                      <p className="mt-2 text-sm text-muted">Select a trace to see its timeline.</p>
                    </div>
                  </div>
                )}
              </main>
            </div>
          )}
        </>
      )}
    </div>
  );
}

function BrandMark() {
  return (
    <svg width="22" height="22" viewBox="0 0 32 32" aria-hidden>
      <rect x="3" y="7" width="16" height="5" rx="2" fill="var(--browser)" />
      <rect x="9" y="14" width="20" height="5" rx="2" fill="var(--server)" />
      <rect x="3" y="21" width="12" height="5" rx="2" fill="var(--browser)" />
    </svg>
  );
}

function LiveDot({ ok }: { ok: boolean }) {
  return (
    <span aria-hidden className="relative inline-flex h-2 w-2">
      {ok && <span className="absolute inset-0 animate-[ping-soft_2.4s_var(--ease-out-expo)_infinite] rounded-full bg-ok" />}
      <span className={`relative h-2 w-2 rounded-full ${ok ? 'bg-ok' : 'bg-error'}`} />
    </span>
  );
}

function SunIcon() {
  return (
    <svg aria-hidden width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
      <circle cx="12" cy="12" r="4.2" />
      <path d="M12 2.5v2.2M12 19.3v2.2M4.6 4.6l1.6 1.6M17.8 17.8l1.6 1.6M2.5 12h2.2M19.3 12h2.2M4.6 19.4l1.6-1.6M17.8 6.2l1.6-1.6" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg aria-hidden width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round">
      <path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z" />
    </svg>
  );
}
