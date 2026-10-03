// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from 'react';
import {
  ApiError,
  continueReplaySession,
  fetchComparison,
  fetchReplaySession,
  fetchReplayable,
  fetchReplayTargets,
  replay,
  startReplaySession,
} from './api';
import type {
  Change,
  Comparison,
  ComparisonResult,
  PausedArgument,
  ReplayOutcome,
  ReplaySession,
  ReplayTarget,
  Replayable,
  SpanRow,
} from './api';
import { formatDuration } from './format';

const COMPARE_TIMEOUT_MS = 10_000;

type Phase =
  | { kind: 'idle' }
  | { kind: 'sending' }
  | { kind: 'stepping'; session: ReplaySession }
  | { kind: 'comparing'; outcome: ReplayOutcome }
  | { kind: 'done'; outcome: ReplayOutcome; comparison: Comparison | undefined; edited?: string[]; paused?: boolean }
  | { kind: 'failed'; message: string };

/** Replay a captured request against a target and compare the runs (PRD section 8). */
export function ReplayPanel({
  traceId,
  token,
  capturedBodies = {},
  spans = [],
}: {
  traceId: string;
  token: string | undefined;
  /** Request bodies as shown in the trace, by server span ID, to prefill an edit. */
  capturedBodies?: Record<string, string>;
  /** The trace's spans, to offer the steps a replay can pause at. */
  spans?: SpanRow[];
}) {
  const [replayable, setReplayable] = useState<Replayable[]>([]);
  const [targets, setTargets] = useState<ReplayTarget[]>([]);
  const [open, setOpen] = useState(false);
  const [chosenSpanId, setChosenSpanId] = useState<string>();

  useEffect(() => {
    let cancelled = false;
    Promise.all([fetchReplayable(traceId, token), fetchReplayTargets(token)])
      .then(([requests, available]) => {
        if (!cancelled) {
          setReplayable(requests);
          setTargets(available);
        }
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [traceId, token]);

  const request = replayable.find((r) => r.spanId === chosenSpanId) ?? replayable[0];
  if (!request) {
    return null;
  }
  return (
    <section aria-label="Replay" className="panel mx-6 mt-5 p-4 text-sm">
      <div className="flex flex-wrap items-center gap-3">
        <span aria-hidden className="grid h-7 w-7 place-items-center rounded-full border border-server/40 text-server">
          <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden>
            <path fill="currentColor" d="M8 3a5 5 0 1 0 4.9 6h-2.08A3 3 0 1 1 8 5v2.5L11.5 4 8 .5V3z" />
          </svg>
        </span>
        <h3 className="font-semibold">Replay</h3>
        {replayable.length === 1 ? (
          <span className="font-mono text-[12px] text-muted">
            {request.method} {request.route}
          </span>
        ) : (
          // A trace can hold several server requests, e.g. two API calls from one click.
          <select
            aria-label="Request to replay"
            value={request.spanId}
            onChange={(e) => setChosenSpanId(e.target.value)}
            className="rounded-lg border border-line bg-paper-2 px-2 py-1 font-mono text-[12px] text-ink"
          >
            {replayable.map((r, i) => (
              <option key={r.spanId} value={r.spanId}>
                {i + 1}. {r.method} {r.route}
              </option>
            ))}
          </select>
        )}
        {!open && (
          <button
            type="button"
            onClick={() => setOpen(true)}
            className="btn btn-primary ml-auto"
          >
            Replay…
          </button>
        )}
      </div>
      {open && (
        <ReplayForm
          key={request.spanId}
          traceId={traceId}
          token={token}
          request={request}
          targets={targets}
          capturedBody={capturedBodies[request.spanId]}
          steps={pausableSteps(spans, request.spanId)}
        />
      )}
    </section>
  );
}

/** Controller and @Observed service spans inside the replayed request: where a replay can stop. */
export function pausableSteps(spans: SpanRow[], requestSpanId: string): SpanRow[] {
  const inside = new Set([requestSpanId]);
  const steps: SpanRow[] = [];
  // Spans come in tree order, so a parent is always seen before its children.
  for (const span of spans) {
    if (span.parentSpanId && inside.has(span.parentSpanId)) {
      inside.add(span.spanId);
      if ((span.kind === 'CONTROLLER' || span.kind === 'SERVICE') && span.attributes['code.namespace'] && span.attributes['code.function']) {
        steps.push(span);
      }
    }
  }
  return steps;
}

function ReplayForm({
  traceId,
  token,
  request,
  targets,
  capturedBody,
  steps,
}: {
  traceId: string;
  token: string | undefined;
  request: Replayable;
  targets: ReplayTarget[];
  capturedBody?: string;
  steps: SpanRow[];
}) {
  const [pausing, setPausing] = useState(false);
  const [breakpoints, setBreakpoints] = useState<string[]>([]);
  const [target, setTarget] = useState(targets[0]?.name ?? 'local');
  const [confirmed, setConfirmed] = useState(false);
  const [editing, setEditing] = useState(false);
  const [body, setBody] = useState(capturedBody ?? '');
  const sendBody = request.bodyMissing || editing;
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const chosen = targets.find((t) => t.name === target);
  const blocked =
    request.queryMissing ||
    (sendBody && body.trim() === '') ||
    (request.unsafe && !confirmed) ||
    (pausing && breakpoints.length === 0);
  const canPause = target === 'local' && steps.length > 0;

  const finish = async (outcome: ReplayOutcome, edited?: string[], paused?: boolean) => {
    setPhase({ kind: 'comparing', outcome });
    const comparison = chosen?.comparable ? await awaitComparison(traceId, request.spanId, outcome, token) : undefined;
    setPhase({ kind: 'done', outcome, comparison, edited, paused });
  };

  // While a paused replay runs, follow it: it may stop, time out, finish or fail at any moment.
  const sessionId = phase.kind === 'stepping' ? phase.session.id : undefined;
  useEffect(() => {
    if (!sessionId) {
      return undefined;
    }
    let cancelled = false;
    const timer = setInterval(() => {
      fetchReplaySession(sessionId, token)
        .then((session) => {
          if (cancelled) {
            return;
          }
          if (session.state === 'DONE' && session.outcome) {
            cancelled = true;
            void finish(session.outcome, session.edited, true);
          } else if (session.state === 'FAILED') {
            cancelled = true;
            setPhase({ kind: 'failed', message: session.error ?? 'The replay failed.' });
          } else {
            setPhase((current) =>
              current.kind === 'stepping' && samePause(current.session, session) ? current : { kind: 'stepping', session },
            );
          }
        })
        .catch(() => {});
    }, 500);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- follows one session for as long as it runs
  }, [sessionId, token]);

  const run = async () => {
    setPhase({ kind: 'sending' });
    if (pausing && canPause) {
      try {
        const session = await startReplaySession(
          { traceId, spanId: request.spanId, confirm: confirmed, body: sendBody ? body : undefined, breakpoints },
          token,
        );
        setPhase({ kind: 'stepping', session });
      } catch (e) {
        setPhase({ kind: 'failed', message: e instanceof Error ? e.message : String(e) });
      }
      return;
    }
    try {
      const outcome = await replay(
        { traceId, spanId: request.spanId, target, confirm: confirmed, body: sendBody ? body : undefined },
        token,
      );
      await finish(outcome);
    } catch (e) {
      setPhase({ kind: 'failed', message: e instanceof Error ? e.message : String(e) });
    }
  };

  return (
    <div className="mt-4 space-y-3 border-t border-line-soft pt-4">
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
            <option key={t.name} value={t.name}>
              {t.name} ({t.location}, auth: {t.auth})
            </option>
          ))}
        </select>
      </label>

      <ul className="space-y-1 text-xs text-ink-2">
        <li>{credentialsNote(chosen)}</li>
        {request.hasBody && !request.bodyMissing && !editing && (
          <li>Body: captured ({request.bodyBytes} bytes), sent as it was.</li>
        )}
        {request.queryMissing && (
          <li className="text-error">
            The query string was not captured, so this request can't be replayed. Set{' '}
            <code>causeline.capture.request-body=replay-only</code>.
          </li>
        )}
      </ul>

      {!request.bodyMissing && request.hasBody && (
        <label className="flex items-center gap-2 text-xs text-ink-2">
          <input
            type="checkbox"
            checked={editing}
            onChange={(e) => setEditing(e.target.checked)}
            className="accent-[var(--signal)]"
          />
          Edit the body before sending, e.g. to try a fix or a different value
        </label>
      )}
      {sendBody && (
        <label className="block">
          {request.bodyMissing && (
            <span className="text-xs text-muted">
              The body was not captured (<code>causeline.capture.request-body</code> is <code>none</code>). Paste one to
              replay:
            </span>
          )}
          <textarea
            aria-label="Body to send"
            value={body}
            onChange={(e) => setBody(e.target.value)}
            rows={6}
            spellCheck={false}
            className="mt-1 w-full rounded-lg border border-line bg-paper-2 p-3 font-mono text-[12px] text-ink focus:border-ink/50 focus:outline-none"
          />
        </label>
      )}

      {canPause && (
        <div className="rounded-lg border border-line-soft p-3">
          <label className="flex items-center gap-2 text-xs text-ink-2">
            <input
              type="checkbox"
              checked={pausing}
              onChange={(e) => setPausing(e.target.checked)}
              className="accent-[var(--signal)]"
            />
            Pause at steps and change their arguments, like breakpoints
          </label>
          {pausing && (
            <ul aria-label="Steps to pause at" className="mt-2 space-y-1">
              {steps.map((step) => (
                <li key={step.spanId}>
                  <label className="flex items-center gap-2 font-mono text-[12px] text-ink">
                    <input
                      type="checkbox"
                      checked={breakpoints.includes(step.spanId)}
                      onChange={(e) =>
                        setBreakpoints((current) =>
                          e.target.checked ? [...current, step.spanId] : current.filter((id) => id !== step.spanId),
                        )
                      }
                    />
                    <span className="w-20 shrink-0 text-[10px] tracking-wide text-server uppercase">
                      {step.kind === 'CONTROLLER' ? 'Controller' : 'Service'}
                    </span>
                    {step.name}
                  </label>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {request.unsafe && (
        <label className="flex items-start gap-2 rounded-lg border border-warn/40 bg-warn/10 p-3 text-xs text-ink-2">
          <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
          <span>
            This will run <strong>
              {request.method} {request.route}
            </strong>{' '}
            on <strong>{target}</strong> again. Side effects such as orders and payments will repeat.
          </span>
        </label>
      )}

      <button
        type="button"
        onClick={() => void run()}
        disabled={blocked || phase.kind === 'sending' || phase.kind === 'comparing' || phase.kind === 'stepping'}
        className="btn btn-primary disabled:cursor-not-allowed disabled:opacity-40"
      >
        {phase.kind === 'sending'
          ? 'Replaying…'
          : phase.kind === 'comparing'
            ? 'Comparing…'
            : phase.kind === 'stepping'
              ? 'Replay running…'
              : pausing && canPause
                ? 'Replay and pause'
                : 'Replay now'}
      </button>

      {phase.kind === 'stepping' && (
        <SessionPanel session={phase.session} token={token} onUpdate={(session) => setPhase({ kind: 'stepping', session })} />
      )}

      {phase.kind === 'failed' && (
        <p role="alert" className="text-xs text-error">
          {phase.message}
        </p>
      )}
      {phase.kind === 'done' && (
        <>
          {phase.edited && phase.edited.length > 0 && (
            <p className="text-xs text-server">Ran with changed arguments at {phase.edited.join(', ')}.</p>
          )}
          {phase.paused && (
            <p className="text-xs text-muted">
              The replay's timings include the time it spent paused, so the steps from the pause onwards look slower.
            </p>
          )}
          <ReplayResult outcome={phase.outcome} comparison={phase.comparison} />
        </>
      )}
    </div>
  );
}

/** Says exactly which credentials the replay will carry to the chosen target. */
export function credentialsNote(target: ReplayTarget | undefined): string {
  if (!target) {
    return '';
  }
  const profile = target.auth !== 'none';
  if (target.sendsOriginalCredentials) {
    return profile
      ? `The original headers and cookies are resent; ${target.name}'s ${target.auth} credentials replace the original Authorization.`
      : "The original headers are resent as they were, including the user's Authorization and cookies.";
  }
  return profile
    ? `Original credentials and cookies are not sent; ${target.name}'s ${target.auth} credentials are used instead.`
    : 'Original credentials and cookies are not sent, and this target has no auth profile.';
}

async function awaitComparison(
  traceId: string,
  spanId: string,
  outcome: ReplayOutcome,
  token: string | undefined,
): Promise<Comparison | undefined> {
  const deadline = Date.now() + COMPARE_TIMEOUT_MS;
  let last: Comparison | undefined;
  while (Date.now() < deadline) {
    try {
      last = await fetchComparison({ traceId, spanId, replayTraceId: outcome.replayTraceId, target: outcome.target }, token);
      if (!last.instrumented) {
        return last;
      }
      // Spans keep arriving for a moment after the response; wait until the tree stops growing.
      await delay(600);
      const settled = await fetchComparison(
        { traceId, spanId, replayTraceId: outcome.replayTraceId, target: outcome.target },
        token,
      );
      if (settled.result?.rows.length === last.result?.rows.length) {
        return settled;
      }
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 404)) {
        throw e;
      }
    }
    await delay(400);
  }
  return last;
}

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const CHANGE_STYLE: Record<Change, string> = {
  SAME: '',
  FASTER: 'bg-ok/10',
  SLOWER: 'bg-warn/10',
  STATUS_CHANGED: 'bg-error/10',
  ONLY_IN_ORIGINAL: 'bg-muted/10',
  ONLY_IN_REPLAY: 'bg-browser/10',
};

const CHANGE_LABEL: Record<Change, string> = {
  SAME: '',
  FASTER: 'faster',
  SLOWER: 'slower',
  STATUS_CHANGED: 'status changed',
  ONLY_IN_ORIGINAL: 'only in original',
  ONLY_IN_REPLAY: 'only in replay',
};

export function ReplayResult({ outcome, comparison }: { outcome: ReplayOutcome; comparison: Comparison | undefined }) {
  const result = comparison?.result;
  return (
    <div aria-label="Comparison" className="space-y-2 rounded-lg border border-line-soft bg-paper-2 p-3">
      <p className="text-xs">
        Replay answered <strong>HTTP {outcome.httpStatus}</strong> in {formatDuration(outcome.durationNanos)} on{' '}
        {outcome.target}.
        {outcome.sentRedactedFields && ' Sensitive fields were sent as [REDACTED], which can change the outcome.'}
      </p>
      {!result && (
        <p className="text-xs text-muted">
          {comparison && !comparison.instrumented
            ? `No span-by-span comparison: the target "${outcome.target}" has no Causeline token configured (causeline.replay.targets.${outcome.target}.causeline-token).`
            : "The replay's trace hasn't arrived; open it from the trace list when it does."}
        </p>
      )}
      {result && <ComparisonTable result={result} left="Original" right="Replay" />}
    </div>
  );
}

/** Two runs side by side, span by span, with what changed. */
export function ComparisonTable({ result, left, right }: { result: ComparisonResult; left: string; right: string }) {
  const label = (change: Change) =>
    change === 'ONLY_IN_ORIGINAL' ? `only in ${left.toLowerCase()}` : change === 'ONLY_IN_REPLAY' ? `only in ${right.toLowerCase()}` : CHANGE_LABEL[change];
  return (
    <table className="w-full table-fixed text-xs">
      <thead>
        <tr className="text-left text-muted">
          <th className="w-1/2 py-1 font-medium">Operation</th>
          <th className="py-1 text-right font-medium">
            {left}
            {result.original.httpStatus ? ` (${result.original.httpStatus})` : ''}
          </th>
          <th className="py-1 text-right font-medium">
            {right}
            {result.replay.httpStatus ? ` (${result.replay.httpStatus})` : ''}
          </th>
          <th className="w-28 py-1 pl-2 font-medium">Change</th>
        </tr>
      </thead>
      <tbody>
        {result.rows.map((row, i) => (
          <tr key={i} className={`border-t border-line-soft ${CHANGE_STYLE[row.change]}`}>
            <td className="truncate py-1" style={{ paddingLeft: row.depth * 14 }} title={row.name}>
              {row.name}
            </td>
            <td className={`py-1 text-right tabular-nums ${row.originalStatus === 'ERROR' ? 'text-error' : ''}`}>
              {row.originalNanos === null ? '—' : formatDuration(row.originalNanos)}
            </td>
            <td className={`py-1 text-right tabular-nums ${row.replayStatus === 'ERROR' ? 'text-error' : ''}`}>
              {row.replayNanos === null ? '—' : formatDuration(row.replayNanos)}
            </td>
            <td className="py-1 pl-2 text-ink-2">{label(row.change)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

const samePause = (a: ReplaySession, b: ReplaySession) =>
  a.state === b.state && a.paused?.step === b.paused?.step && a.paused?.since === b.paused?.since;

function pretty(json: string): string {
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}

/** True when the edited text means something different from the captured value. */
function changed(original: string, edited: string): boolean {
  try {
    return JSON.stringify(JSON.parse(original)) !== JSON.stringify(JSON.parse(edited));
  } catch {
    return original.trim() !== edited.trim();
  }
}

/** A replay in progress: where it stopped, with the method's arguments ready to edit. */
function SessionPanel({
  session,
  token,
  onUpdate,
}: {
  session: ReplaySession;
  token: string | undefined;
  onUpdate: (session: ReplaySession) => void;
}) {
  const paused = session.state === 'PAUSED' ? session.paused : null;
  if (!paused) {
    return (
      <p aria-live="polite" className="flex items-center gap-2 text-xs text-muted">
        <span aria-hidden className="h-2 w-2 animate-pulse rounded-full bg-server" />
        Running to the next step…
      </p>
    );
  }
  return <PausedStep key={`${paused.step}#${paused.occurrence}@${paused.since}`} id={session.id} paused={paused} token={token} onUpdate={onUpdate} />;
}

function PausedStep({
  id,
  paused,
  token,
  onUpdate,
}: {
  id: string;
  paused: NonNullable<ReplaySession['paused']>;
  token: string | undefined;
  onUpdate: (session: ReplaySession) => void;
}) {
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(paused.arguments.map((a) => [a.name, pretty(a.json)])),
  );
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const resume = async (skipRest: boolean) => {
    setBusy(true);
    setError(undefined);
    const edits = Object.fromEntries(
      paused.arguments.filter((a) => a.editable && changed(a.json, values[a.name] ?? a.json)).map((a) => [a.name, values[a.name] ?? a.json]),
    );
    try {
      onUpdate(await continueReplaySession(id, { arguments: edits, skipRest }, token));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-label="Paused replay" className="fade-up space-y-3 rounded-lg border border-server/50 bg-server/5 p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="chip border-server/50 font-mono text-[10px] tracking-wide text-server uppercase">Paused</span>
        <span className="font-mono text-[13px] text-ink">
          {paused.step}
          {paused.occurrence > 1 && <span className="text-muted"> (call {paused.occurrence})</span>}
        </span>
        <span className="text-xs text-muted">The request is waiting here. Change the arguments, then continue.</span>
      </div>
      {paused.arguments.length === 0 && <p className="text-xs text-muted">This method takes no arguments.</p>}
      {paused.arguments.map((argument: PausedArgument) => (
        <label key={argument.name} className="block">
          <span className="flex items-baseline gap-2 text-xs">
            <span className="font-mono text-ink">{argument.name}</span>
            <span className="truncate font-mono text-[10px] text-muted" title={argument.type}>
              {argument.type}
            </span>
            {!argument.editable && <span className="text-[10px] text-muted">(shown by type; passed on unchanged)</span>}
          </span>
          <textarea
            aria-label={`Argument ${argument.name}`}
            value={values[argument.name] ?? ''}
            readOnly={!argument.editable}
            onChange={(e) => setValues((current) => ({ ...current, [argument.name]: e.target.value }))}
            rows={Math.min(10, Math.max(1, (values[argument.name] ?? '').split('\n').length))}
            spellCheck={false}
            className="mt-1 w-full rounded-lg border border-line bg-paper-2 p-2 font-mono text-[12px] text-ink read-only:opacity-60 focus:border-ink/50 focus:outline-none"
          />
        </label>
      ))}
      {error && (
        <p role="alert" className="text-xs text-error">
          {error}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <button type="button" disabled={busy} onClick={() => void resume(false)} className="btn btn-primary disabled:opacity-40">
          Continue
        </button>
        <button type="button" disabled={busy} onClick={() => void resume(true)} className="btn disabled:opacity-40">
          Continue without stopping again
        </button>
      </div>
    </section>
  );
}
