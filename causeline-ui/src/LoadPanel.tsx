// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from 'react';
import { cancelLoadRun, fetchLoadLimits, fetchLoadRun, startLoadRun } from './api';
import type { LoadLimits, LoadMode, LoadRun, LoadShape, LoadStats, LoadStep, Replayable, ReplayTarget, SpanRow } from './api';
import { formatDuration } from './format';
import type { RequestDraft } from './ReplayPanel';
import { requestEdits } from './ReplayPanel';
import { capturedRequest, rampLevels, toK6 } from './snippets';
import { LOAD_HINTS, UsageHints } from './UsageHints';
import type { K6Step } from './snippets';

/** The longest pause between two requests of a flow, as the server caps it. */
const MAX_THINK_MS = 5_000;
const POLL_MS = 500;

/** A request of the flow, in the order the user sent them, with the pause before it. */
export interface FlowStep {
  span: SpanRow;
  replayable: Replayable;
  thinkMillis: number;
}

/**
 * The trace's replayable server requests in the order they started, each with the gap after the
 * previous response: what a flow load replay sends. The server works this out again from its own
 * clock; this is the preview and the k6 export.
 */
export function flowSteps(spans: SpanRow[], replayable: Replayable[]): FlowStep[] {
  const byId = new Map(replayable.map((r) => [r.spanId, r]));
  const requests = spans
    .filter((s) => s.kind === 'REQUEST' && s.source !== 'browser' && byId.has(s.spanId))
    .sort((a, b) => a.offsetNanos - b.offsetNanos);
  return requests.map((span, i) => {
    const previous = requests[i - 1];
    const gap = previous ? span.offsetNanos - (previous.offsetNanos + previous.durationNanos) : 0;
    return {
      span,
      replayable: byId.get(span.spanId) as Replayable,
      thinkMillis: Math.min(MAX_THINK_MS, Math.max(0, Math.round(gap / 1_000_000))),
    };
  });
}

export interface LoadForm {
  mode: LoadMode;
  shape: LoadShape;
  hits: number;
  concurrency: number;
  rate: number;
  durationSeconds: number;
  ramp: boolean;
}

/** Iterations of the load phase, as the server plans them (a ramp splits the hits across its levels). */
export function iterations(form: LoadForm): number {
  if (form.shape === 'RATE') {
    return Math.ceil(form.rate * form.durationSeconds);
  }
  if (!form.ramp) {
    return form.hits;
  }
  const levels = rampLevels(form.concurrency).length;
  return Math.max(1, Math.floor(form.hits / levels)) * levels;
}

/** HTTP requests the run sends, baseline included: what `causeline.load.max-requests` limits. */
export function plannedRequests(form: LoadForm, steps: number, limits: Pick<LoadLimits, 'baselineHits'>): number {
  return (limits.baselineHits + iterations(form)) * steps;
}

/** Why the form can't be sent yet, in the server's words where it has them. */
export function formProblem(form: LoadForm, steps: number, limits: LoadLimits): string | undefined {
  if (form.concurrency < 1 || form.concurrency > limits.maxConcurrency) {
    return `In flight must be between 1 and ${limits.maxConcurrency} (causeline.load.max-concurrency).`;
  }
  if (form.shape === 'RATE') {
    if (!(form.rate > 0)) {
      return 'The rate must be above 0.';
    }
    if (form.durationSeconds < 1 || form.durationSeconds > limits.maxDurationSeconds) {
      return `The duration must be between 1 and ${limits.maxDurationSeconds} s (causeline.load.max-duration).`;
    }
  } else if (form.hits < 1) {
    return 'Send at least one hit.';
  }
  const requests = plannedRequests(form, steps, limits);
  if (requests > limits.maxRequests) {
    return `This run would send ${requests} requests; causeline.load.max-requests is ${limits.maxRequests}.`;
  }
  return undefined;
}

/** The confirmation for side effects, naming how often they repeat. */
export function confirmationText(method: string, route: string, times: number, target: string): string {
  return `This will run ${method} ${route} ${times} times on ${target}. Side effects such as orders and payments will repeat ${times} times.`;
}

const DEFAULT_LIMITS: LoadLimits = { maxRequests: 1000, maxConcurrency: 20, maxDurationSeconds: 60, baselineHits: 5 };

/** The Load tab: send the request, or the flow, many times and see which span grows (PRD section 8). */
export function LoadPanel({
  traceId,
  token,
  request,
  replayable,
  targets,
  spans,
  captured,
  capturedBody,
  onOpenTrace,
}: {
  traceId: string;
  token: string | undefined;
  request: Replayable;
  replayable: Replayable[];
  targets: ReplayTarget[];
  spans: SpanRow[];
  captured: RequestDraft;
  capturedBody?: string;
  onOpenTrace?: (traceId: string) => void;
}) {
  const loadable = targets.filter((t) => t.allowsLoad);
  const [limits, setLimits] = useState<LoadLimits>(DEFAULT_LIMITS);
  const [target, setTarget] = useState(loadable[0]?.name ?? 'local');
  const [form, setForm] = useState<LoadForm>({
    mode: 'REQUEST',
    shape: 'CONCURRENCY',
    hits: 100,
    concurrency: 10,
    rate: 10,
    durationSeconds: 10,
    ramp: false,
  });
  const [confirmed, setConfirmed] = useState(false);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<RequestDraft>(captured);
  const [body, setBody] = useState(capturedBody ?? '');
  const [run, setRun] = useState<LoadRun>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    fetchLoadLimits(token).then(setLimits).catch(() => {});
  }, [token]);

  // Follow the run until it has finished and every hit's trace was folded in.
  const following = run && (run.state === 'RUNNING' || run.tracesPending > 0) ? run.id : undefined;
  useEffect(() => {
    if (!following) {
      return undefined;
    }
    let cancelled = false;
    const timer = setInterval(() => {
      fetchLoadRun(following, token)
        .then((next) => !cancelled && setRun(next))
        .catch(() => {});
    }, POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [following, token]);

  const flow = flowSteps(spans, replayable);
  const steps: { method: string; route: string; unsafe: boolean; thinkMillis: number; span?: SpanRow }[] =
    form.mode === 'FLOW'
      ? flow.map((s) => ({ ...s.replayable, thinkMillis: s.thinkMillis, span: s.span }))
      : [{ ...request, thinkMillis: 0, span: spans.find((s) => s.spanId === request.spanId) }];
  const problem = formProblem(form, steps.length, limits);
  const unsafe = steps.find((s) => s.unsafe);
  const times = limits.baselineHits + iterations(form);
  const edited = editing ? requestEdits(captured, draft) : { edits: {}, changed: [] };
  const editError = 'error' in edited ? edited.error : undefined;
  const sendBody = request.bodyMissing || (editing && body !== (capturedBody ?? ''));
  const running = run?.state === 'RUNNING';
  const blocked =
    busy || running || problem !== undefined || editError !== undefined || (unsafe !== undefined && !confirmed) || !loadable.length;

  const start = async () => {
    setBusy(true);
    setError(undefined);
    try {
      setRun(
        await startLoadRun(
          {
            traceId,
            spanId: request.spanId,
            target,
            ...form,
            confirm: confirmed,
            body: sendBody ? body : undefined,
            ...('error' in edited ? {} : edited.edits),
          },
          token,
        ),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const copyK6 = () => {
    const k6Steps: K6Step[] = steps.flatMap((s) => {
      const captured = s.span ? capturedRequest(s.span) : undefined;
      return captured ? [{ request: captured, thinkMillis: s.thinkMillis }] : [];
    });
    void navigator.clipboard?.writeText(toK6(k6Steps, { ...form }, window.location.origin)).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };

  const input = 'w-20 rounded-lg border border-line bg-paper-2 px-2 py-1 text-right tabular-nums text-[13px] text-ink';
  const set = (change: Partial<LoadForm>) => {
    setForm({ ...form, ...change });
    setConfirmed(false);
  };

  return (
    <div className="mt-4 space-y-3 border-t border-line-soft pt-4" aria-label="Load replay">
      <p className="text-xs text-ink-2">
        Sends the request many times at once. Each hit is a replay with its own trace, so the result shows <em>which span</em>{' '}
        slows down under concurrency. It runs inside this application, on this machine: compare runs with each other, not
        with production.
      </p>
      <UsageHints label="How to use a load test" hints={LOAD_HINTS} />

      <label className="flex items-center gap-2">
        <span className="w-20 text-xs text-muted">Target</span>
        <select
          value={target}
          onChange={(e) => {
            setTarget(e.target.value);
            setConfirmed(false);
          }}
          className="rounded-lg border border-line bg-paper-2 px-2 py-1 text-[13px] text-ink"
        >
          {targets.map((t) => (
            <option key={t.name} value={t.name} disabled={!t.allowsLoad}>
              {t.name} ({t.location}){t.allowsLoad ? '' : ', allow-load is off'}
            </option>
          ))}
        </select>
      </label>
      {target !== 'local' && (
        <p className="text-xs text-muted">
          Not this application: the result has each hit's status and time, but no span analysis.
        </p>
      )}

      <fieldset className="flex flex-wrap items-center gap-4 text-xs text-ink-2">
        <legend className="sr-only">What to send</legend>
        <span className="w-20 text-muted">Send</span>
        <label className="flex items-center gap-1">
          <input type="radio" checked={form.mode === 'REQUEST'} onChange={() => set({ mode: 'REQUEST' })} className="accent-[var(--signal)]" />
          This request
        </label>
        <label className="flex items-center gap-1">
          <input
            type="radio"
            checked={form.mode === 'FLOW'}
            disabled={flow.length < 2}
            onChange={() => set({ mode: 'FLOW' })}
            className="accent-[var(--signal)]"
          />
          The whole flow ({flow.length} requests, as one user)
        </label>
      </fieldset>
      {form.mode === 'FLOW' && (
        <ol className="ml-24 list-decimal space-y-0.5 font-mono text-[12px] text-ink-2">
          {flow.map((s) => (
            <li key={s.span.spanId}>
              {s.thinkMillis > 0 && <span className="text-muted">wait {formatDuration(s.thinkMillis * 1_000_000)}, then </span>}
              {s.replayable.method} {s.replayable.route}
            </li>
          ))}
        </ol>
      )}

      <fieldset className="flex flex-wrap items-center gap-4 text-xs text-ink-2">
        <legend className="sr-only">Shape</legend>
        <span className="w-20 text-muted">Shape</span>
        <label className="flex items-center gap-1">
          <input type="radio" checked={form.shape === 'CONCURRENCY'} onChange={() => set({ shape: 'CONCURRENCY' })} className="accent-[var(--signal)]" />
          Fixed in flight
        </label>
        <label className="flex items-center gap-1">
          <input type="radio" checked={form.shape === 'RATE'} onChange={() => set({ shape: 'RATE', ramp: false })} className="accent-[var(--signal)]" />
          Fixed rate
        </label>
      </fieldset>
      <div className="ml-24 flex flex-wrap items-center gap-4 text-xs text-ink-2">
        {form.shape === 'CONCURRENCY' ? (
          <>
            <label className="flex items-center gap-1">
              <input aria-label="Hits" type="number" min={1} value={form.hits} onChange={(e) => set({ hits: Number(e.target.value) })} className={input} />
              hits
            </label>
            <label className="flex items-center gap-1">
              <input aria-label="In flight" type="number" min={1} max={limits.maxConcurrency} value={form.concurrency} onChange={(e) => set({ concurrency: Number(e.target.value) })} className={input} />
              in flight
            </label>
            <label className="flex items-center gap-1">
              <input type="checkbox" checked={form.ramp} onChange={(e) => set({ ramp: e.target.checked })} className="accent-[var(--signal)]" />
              Ramp ({rampLevels(Math.max(1, form.concurrency)).join(', ')})
            </label>
          </>
        ) : (
          <>
            <label className="flex items-center gap-1">
              <input aria-label="Per second" type="number" min={1} value={form.rate} onChange={(e) => set({ rate: Number(e.target.value) })} className={input} />
              per second for
            </label>
            <label className="flex items-center gap-1">
              <input aria-label="Seconds" type="number" min={1} max={limits.maxDurationSeconds} value={form.durationSeconds} onChange={(e) => set({ durationSeconds: Number(e.target.value) })} className={input} />
              s, at most
            </label>
            <label className="flex items-center gap-1">
              <input aria-label="In flight" type="number" min={1} max={limits.maxConcurrency} value={form.concurrency} onChange={(e) => set({ concurrency: Number(e.target.value) })} className={input} />
              in flight
            </label>
          </>
        )}
      </div>
      <p className="text-xs text-muted">
        {limits.baselineHits} baseline hits one at a time first, then the load: {plannedRequests(form, steps.length, limits)} requests in
        all. Limits: {limits.maxRequests} requests, {limits.maxConcurrency} in flight, {limits.maxDurationSeconds} s.
      </p>

      <label className="flex items-center gap-2 text-xs text-ink-2">
        <input type="checkbox" checked={editing} onChange={(e) => setEditing(e.target.checked)} className="accent-[var(--signal)]" />
        <span>Edit {form.mode === 'FLOW' ? `${request.method} ${request.route}` : 'the request'} first; every hit sends the edit.</span>
      </label>
      {editing && (
        <div aria-label="Edit request" className="space-y-2">
          {draft.path !== undefined && (
            <label className="flex items-center gap-2">
              <span className="w-20 shrink-0 text-xs text-muted">Path</span>
              <input aria-label="Path to send" value={draft.path} onChange={(e) => setDraft({ ...draft, path: e.target.value })} spellCheck={false} className="w-full rounded-lg border border-line bg-paper-2 px-2 py-1 font-mono text-[12px] text-ink" />
            </label>
          )}
          <label className="flex items-center gap-2">
            <span className="w-20 shrink-0 text-xs text-muted">Query</span>
            <input aria-label="Query to send" value={draft.query} placeholder="none" onChange={(e) => setDraft({ ...draft, query: e.target.value })} spellCheck={false} className="w-full rounded-lg border border-line bg-paper-2 px-2 py-1 font-mono text-[12px] text-ink" />
          </label>
          <textarea aria-label="Headers to send" value={draft.headers} onChange={(e) => setDraft({ ...draft, headers: e.target.value })} rows={4} spellCheck={false} className="w-full rounded-lg border border-line bg-paper-2 p-2 font-mono text-[12px] text-ink" />
          {(request.hasBody || request.bodyMissing) && (
            <textarea aria-label="Body to send" value={body} onChange={(e) => setBody(e.target.value)} rows={4} spellCheck={false} className="w-full rounded-lg border border-line bg-paper-2 p-2 font-mono text-[12px] text-ink" />
          )}
          {editError && (
            <p role="alert" className="text-xs text-error">
              {editError}
            </p>
          )}
        </div>
      )}

      {unsafe && (
        <label className="flex items-start gap-2 rounded-lg border border-warn/40 bg-warn/5 p-2 text-xs text-ink-2">
          <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} className="mt-0.5 accent-[var(--signal)]" />
          <span>{confirmationText(unsafe.method, unsafe.route, times, target)}</span>
        </label>
      )}
      {problem && <p className="text-xs text-error">{problem}</p>}
      {!loadable.length && <p className="text-xs text-error">No target allows load replay.</p>}

      <div className="flex flex-wrap items-center gap-2">
        <button type="button" onClick={() => void start()} disabled={blocked} className="btn btn-primary disabled:cursor-not-allowed disabled:opacity-40">
          {busy ? 'Starting…' : 'Start load'}
        </button>
        {running && run && (
          <button type="button" onClick={() => void cancelLoadRun(run.id, token).then(setRun)} className="btn">
            Cancel
          </button>
        )}
        <button type="button" onClick={copyK6} className="btn" title="A k6 script with this shape, without captured credentials">
          {copied ? 'Copied' : 'Copy as k6'}
        </button>
        <span className="text-xs text-muted">For real capacity numbers, run the k6 script from another machine.</span>
      </div>
      {error && (
        <p role="alert" className="text-xs text-error">
          {error}
        </p>
      )}
      {run && <LoadResult run={run} onOpenTrace={onOpenTrace} />}
    </div>
  );
}

const ms = (nanos: number | null | undefined) => (nanos === null || nanos === undefined ? '–' : formatDuration(nanos));

/** What a load run found, per request: latency, statuses, failures, and the spans that grew. */
export function LoadResult({ run, onOpenTrace }: { run: LoadRun; onOpenTrace?: (traceId: string) => void }) {
  const done = run.state !== 'RUNNING';
  return (
    <section aria-label="Load result" className="fade-up space-y-4 rounded-lg border border-line p-3">
      <div className="flex flex-wrap items-center gap-3 text-xs">
        <span className={`chip ${run.state === 'RUNNING' ? 'text-server' : 'text-ink'}`}>
          {run.state === 'RUNNING' ? 'Running' : run.state === 'CANCELLED' ? 'Cancelled' : 'Done'}
        </span>
        <span className="tabular-nums text-ink-2">
          {run.sent} / {run.plannedRequests} requests · {formatDuration(run.elapsedMillis * 1_000_000)}
        </span>
        {run.notSent > 0 && <span className="text-warn">{run.notSent} not sent (all {run.concurrency} in flight were busy)</span>}
        {run.tracesMissing > 0 && <span className="text-warn">{run.tracesMissing} hits left no trace</span>}
        {done && run.tracesPending > 0 && <span className="text-muted">Reading {run.tracesPending} traces…</span>}
        {run.stopReason && run.state !== 'CANCELLED' && <span className="text-warn">{run.stopReason}</span>}
      </div>
      {run.skipped.length > 0 && (
        <p className="text-xs text-muted">
          Skipped, nothing captured to replay: {run.skipped.map((s) => s.name).join(', ')}.
        </p>
      )}
      {run.steps.map((step, i) => (
        <StepResult key={step.spanId} step={step} index={run.steps.length > 1 ? i + 1 : undefined} analyzed={run.analyzed} onOpenTrace={onOpenTrace} />
      ))}
      <p className="text-[11px] text-muted">
        Latencies are measured by this application, which also generates the load. They are good for comparing runs and
        spans, not for capacity planning.
      </p>
    </section>
  );
}

function StatsRow({ label, stats }: { label: string; stats: LoadStats | null }) {
  return (
    <tr>
      <td className="py-0.5 text-muted">{label}</td>
      <td className="py-0.5 text-right tabular-nums">{stats?.count ?? 0}</td>
      <td className="py-0.5 text-right tabular-nums">{ms(stats?.p50)}</td>
      <td className="py-0.5 text-right tabular-nums">{ms(stats?.p95)}</td>
      <td className="py-0.5 text-right tabular-nums">{ms(stats?.p99)}</td>
      <td className="py-0.5 text-right tabular-nums">{ms(stats?.max)}</td>
      <td className="py-0.5 text-right tabular-nums">{stats ? stats.perSecond.toFixed(1) : '–'}</td>
    </tr>
  );
}

function StepResult({
  step,
  index,
  analyzed,
  onOpenTrace,
}: {
  step: LoadStep;
  index?: number;
  analyzed: boolean;
  onOpenTrace?: (traceId: string) => void;
}) {
  const statuses = Object.entries(step.loadStatuses);
  const peak = Math.max(1, ...step.timeline.map((b) => b.p95));
  const grew = step.report?.rows.filter((r) => r.grew) ?? [];
  return (
    <div className="space-y-3">
      <h4 className="font-mono text-[12px] text-ink">
        {index !== undefined && `${index}. `}
        {step.method} {step.route}
      </h4>
      <table className="w-full table-fixed text-xs">
        <thead>
          <tr className="text-left text-muted">
            <th className="w-24 font-normal"></th>
            <th className="text-right font-normal">hits</th>
            <th className="text-right font-normal">p50</th>
            <th className="text-right font-normal">p95</th>
            <th className="text-right font-normal">p99</th>
            <th className="text-right font-normal">max</th>
            <th className="text-right font-normal">per s</th>
          </tr>
        </thead>
        <tbody>
          <StatsRow label="Baseline" stats={step.baseline} />
          <StatsRow label="Load" stats={step.load} />
          {step.ramp.map((r) => (
            <StatsRow key={r.concurrency} label={`${r.concurrency} in flight`} stats={r.stats} />
          ))}
        </tbody>
      </table>

      {step.timeline.length > 1 && (
        <div aria-label="p95 per second" className="flex h-10 items-end gap-px" title="p95 per second of the load">
          {step.timeline.map((b) => (
            <span key={b.second} className="flex-1 rounded-sm bg-server/60" style={{ height: `${Math.max(4, (b.p95 / peak) * 100)}%` }} title={`${b.second} s: ${b.hits} hits, p95 ${formatDuration(b.p95)}`} />
          ))}
        </div>
      )}

      <div className="flex flex-wrap gap-2 text-xs">
        {statuses.map(([status, count]) => (
          <span key={status} className={`chip ${status === 'unreachable' || Number(status) >= 500 ? 'text-error' : Number(status) >= 400 ? 'text-warn' : 'text-ok'}`}>
            {status}: {count}
          </span>
        ))}
        {Object.entries(step.errors).map(([message, count]) => (
          <span key={message} className="text-error">
            {count} × {message}
          </span>
        ))}
      </div>

      {step.report && step.report.failures.length > 0 && (
        <ul aria-label="Exceptions" className="space-y-0.5 text-xs">
          {step.report.failures.map((f) => (
            <li key={`${f.exceptionType}|${f.codeLocation}|${f.handled}`} className={f.baselineHits === 0 ? 'text-error' : 'text-ink-2'}>
              <span className="font-mono">{f.exceptionType.split('.').pop()}</span>
              {f.codeLocation && <span className="text-muted"> at {f.codeLocation}</span>}
              {f.handled && <span className="text-muted"> (caught and logged)</span>}: {f.loadHits} under load, {f.baselineHits} in the baseline
              {f.baselineHits === 0 && f.loadHits > 0 && ' — only under concurrency'}
            </li>
          ))}
        </ul>
      )}

      {step.report && (
        <div className="space-y-1">
          <p className="text-xs text-ink-2">
            <span className="font-semibold text-ink">What grew</span>{' '}
            {grew.length === 0
              ? 'Nothing: no span’s self-time p95 grew by 20 ms and 25% under load.'
              : `${grew[0]?.name} grew the most.`}{' '}
            {step.report.baselineDatabaseSpans !== null && step.report.loadDatabaseSpans !== null && (
              <span className={step.report.loadDatabaseSpans > step.report.baselineDatabaseSpans ? 'text-warn' : 'text-muted'}>
                Queries per hit (median): {step.report.baselineDatabaseSpans} → {step.report.loadDatabaseSpans}.
              </span>
            )}
          </p>
          <table className="w-full table-fixed text-xs">
            <thead>
              <tr className="text-left text-muted">
                <th className="font-normal">Span (self time)</th>
                <th className="w-20 text-right font-normal">base p50</th>
                <th className="w-20 text-right font-normal">base p95</th>
                <th className="w-20 text-right font-normal">load p50</th>
                <th className="w-20 text-right font-normal">load p95</th>
                <th className="w-20 text-right font-normal">Δ p95</th>
              </tr>
            </thead>
            <tbody>
              {step.report.rows.slice(0, 15).map((row, i) => {
                const delta = row.baselineP95 !== null && row.loadP95 !== null ? row.loadP95 - row.baselineP95 : null;
                const partial = row.baselineHits < step.report!.baselineHits || row.loadHits < step.report!.loadHits;
                return (
                  <tr key={`${row.name}-${i}`} className={row.grew ? 'bg-error/10 text-ink' : 'text-ink-2'}>
                    <td className="truncate py-0.5 font-mono" style={{ paddingLeft: `${row.depth * 8}px` }} title={row.name}>
                      {row.name}
                      {partial && (
                        <span className="ml-1 text-muted">
                          (in {row.baselineHits}/{step.report!.baselineHits} + {row.loadHits}/{step.report!.loadHits} hits)
                        </span>
                      )}
                    </td>
                    <td className="py-0.5 text-right tabular-nums">{ms(row.baselineP50)}</td>
                    <td className="py-0.5 text-right tabular-nums">{ms(row.baselineP95)}</td>
                    <td className="py-0.5 text-right tabular-nums">{ms(row.loadP50)}</td>
                    <td className="py-0.5 text-right tabular-nums">{ms(row.loadP95)}</td>
                    <td className={`py-0.5 text-right tabular-nums ${row.grew ? 'text-error' : ''}`}>
                      {delta === null ? '–' : `${delta >= 0 ? '+' : '−'}${formatDuration(Math.abs(delta))}`}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {!analyzed && <p className="text-xs text-muted">Span analysis needs the hits' traces, which only a load replay to local has.</p>}

      {step.kept.length > 0 && (
        <p className="text-xs text-ink-2">
          Kept traces:{' '}
          {step.kept.map((k, i) => (
            <span key={k.traceId}>
              {i > 0 && ', '}
              <button type="button" onClick={() => onOpenTrace?.(k.traceId)} className={`underline underline-offset-2 ${k.reason === 'FAILED' ? 'text-error' : 'text-ink'}`}>
                {k.reason === 'FAILED' ? `failed ${k.httpStatus || ''}`.trim() : formatDuration(k.durationNanos)}
              </button>
            </span>
          ))}
          <span className="text-muted"> (the slowest and the failed hits; the rest were folded into the figures above)</span>
        </p>
      )}
    </div>
  );
}
