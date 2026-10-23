// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from 'react';
import {
  ApiError,
  continueReplaySession,
  fetchComparison,
  fetchReplaySession,
  fetchReplayable,
  fetchReplayTargets,
  fetchTrace,
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
  Insight,
  Replayable,
  RequestEdits,
  SpanRow,
} from './api';
import { responseDiffs } from './bodyDiff';
import type { ResponseDiff } from './bodyDiff';
import { formatDuration } from './format';
import { PRESETS, SECURITY_RULES, SecurityGuide, SecurityGuideButton, isSecurityRule, replayVerdict as verdict } from './SecurityGuide';
import type { TryId } from './SecurityGuide';
import { PROBES, PROBE_IDS, applyProbe, defaultParams, isProbe, originalOf, paramsError, probeVerdict, summarizeReplay } from './probes';
import type { OriginalResponse, ProbeId, ProbeParams, ProbeVerdict } from './probes';

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
  onOpenTrace,
}: {
  traceId: string;
  token: string | undefined;
  /** Request bodies as shown in the trace, by server span ID, to prefill an edit. */
  capturedBodies?: Record<string, string>;
  /** The trace's spans, to offer the steps a replay can pause at. */
  spans?: SpanRow[];
  onOpenTrace?: (traceId: string) => void;
}) {
  const [replayable, setReplayable] = useState<Replayable[]>([]);
  const [targets, setTargets] = useState<ReplayTarget[]>([]);
  const [open, setOpen] = useState(false);
  const [chosenSpanId, setChosenSpanId] = useState<string>();
  const [guideOpen, setGuideOpen] = useState(false);
  // The count makes choosing the same case twice apply it again.
  const [preset, setPreset] = useState<{ id: TryId; count: number }>();

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
        <span className="ml-auto flex items-center gap-2">
          <SecurityGuideButton onClick={() => setGuideOpen(true)} label="Try security checks" prominent />
          {!open && (
            <button type="button" onClick={() => setOpen(true)} className="btn btn-primary">
              Replay…
            </button>
          )}
        </span>
      </div>
      <SecurityGuide
        open={guideOpen}
        onClose={() => setGuideOpen(false)}
        canEditBody={request.hasBody}
        token={token}
        onTry={(id) => {
          setOpen(true);
          setPreset((current) => ({ id, count: (current?.count ?? 0) + 1 }));
        }}
      />
      {open && (
        <ReplayForm
          key={request.spanId}
          traceId={traceId}
          token={token}
          request={request}
          targets={targets}
          capturedBody={capturedBodies[request.spanId]}
          captured={draftFrom(spans.find((s) => s.spanId === request.spanId))}
          original={originalOf(spans.find((s) => s.spanId === request.spanId))}
          steps={pausableSteps(spans, request.spanId)}
          preset={preset}
          onShowGuide={() => setGuideOpen(true)}
          onOpenTrace={onOpenTrace}
        />
      )}
    </section>
  );
}

/** Headers HTTP or Causeline sets on every replay; the server ignores them in an edit as well. */
const NOT_EDITABLE = new Set([
  'host',
  'content-length',
  'connection',
  'keep-alive',
  'transfer-encoding',
  'upgrade',
  'te',
  'trailer',
  'expect',
  'http2-settings',
  'traceparent',
  'tracestate',
  'baggage',
  'x-causeline-replay',
]);

/** A request as the edit form shows it: what the server span recorded. */
export interface RequestDraft {
  /** Undefined when the raw path wasn't captured (`capture.path-values=false`); it can't be edited then. */
  path?: string;
  query: string;
  /** One "Name: value" per line. */
  headers: string;
  /** The route template (`/api/orders/{id}`), which says where a path variable sits. */
  route?: string;
}

export function draftFrom(span: SpanRow | undefined): RequestDraft {
  const a = span?.attributes ?? {};
  const headers = Object.entries(a)
    .filter(([key]) => key.startsWith('http.request.header.'))
    .map(([key, value]) => [key.slice('http.request.header.'.length), value] as [string, string])
    .filter(([name]) => !NOT_EDITABLE.has(name))
    .map(([name, value]) => `${name}: ${value}`);
  return { path: a['url.path'], query: a['url.query'] ?? '', headers: headers.join('\n'), route: a['http.route'] };
}

/** Reads "Name: value" lines; blank lines are skipped. */
export function parseHeaders(text: string): { headers: Record<string, string> } | { error: string } {
  const headers: Record<string, string> = {};
  for (const [i, raw] of text.split(/\r?\n/).entries()) {
    const line = raw.trim();
    if (line === '') {
      continue;
    }
    const colon = line.indexOf(':');
    const name = colon > 0 ? line.slice(0, colon).trim() : '';
    if (!/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(name)) {
      return { error: `Line ${i + 1} is not "Name: value".` };
    }
    headers[name] = line.slice(colon + 1).trim();
  }
  return { headers };
}

/** Only the parts that differ from what was captured are sent, so the rest replays exactly as it was. */
export function requestEdits(
  captured: RequestDraft,
  draft: RequestDraft,
): { edits: RequestEdits; changed: string[] } | { error: string } {
  const edits: RequestEdits = {};
  const changed: string[] = [];
  if (draft.path !== undefined && draft.path !== captured.path) {
    edits.path = draft.path.trim();
    changed.push('path');
  }
  if (draft.query !== captured.query) {
    edits.query = draft.query.trim().replace(/^\?/, '');
    changed.push('query');
  }
  if (draft.headers !== captured.headers) {
    const parsed = parseHeaders(draft.headers);
    if ('error' in parsed) {
      return parsed;
    }
    edits.headers = parsed.headers;
    changed.push('headers');
  }
  return { edits, changed };
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
  captured,
  original,
  steps,
  preset,
  onShowGuide,
  onOpenTrace,
}: {
  traceId: string;
  token: string | undefined;
  request: Replayable;
  targets: ReplayTarget[];
  capturedBody?: string;
  captured: RequestDraft;
  /** What the original request answered, to compare a probe's replay with. */
  original: OriginalResponse;
  steps: SpanRow[];
  /** A security case chosen in the guide, to fill into the form. */
  preset?: { id: TryId; count: number };
  onShowGuide: () => void;
  onOpenTrace?: (traceId: string) => void;
}) {
  const [pausing, setPausing] = useState(false);
  const [breakpoints, setBreakpoints] = useState<string[]>([]);
  const [target, setTarget] = useState(targets[0]?.name ?? 'local');
  const [confirmed, setConfirmed] = useState(false);
  const [editing, setEditing] = useState(false);
  const [body, setBody] = useState(capturedBody ?? '');
  const [draft, setDraft] = useState<RequestDraft>(captured);
  const [filledIn, setFilledIn] = useState<string>();
  // The probe being set up, and the one last applied to the request: the verdict is about the latter.
  const [probe, setProbe] = useState<{ id: ProbeId; params: ProbeParams }>();
  const [applied, setApplied] = useState<{ id: ProbeId; params: ProbeParams }>();
  const [probeError, setProbeError] = useState<string>();

  useEffect(() => {
    if (!preset) {
      return;
    }
    setEditing(true);
    if (isProbe(preset.id)) {
      // A probe needs input first (an ID, a field, a header), so it is set up here, not applied at once.
      setProbe({ id: preset.id, params: defaultParams(preset.id) });
      setProbeError(undefined);
      return;
    }
    const next = PRESETS[preset.id].apply({ draft, body });
    setDraft(next.draft);
    setBody(next.body);
    setFilledIn(PRESETS[preset.id].label);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- applies each choice once, to the form as it is then
  }, [preset?.count]);

  const applyCurrentProbe = () => {
    if (!probe) {
      return;
    }
    const result = applyProbe(probe.id, probe.params, { draft, body });
    if ('error' in result) {
      setProbeError(result.error);
      return;
    }
    setProbeError(undefined);
    setDraft(result.draft);
    setBody(result.body);
    setApplied(probe);
    setFilledIn(`Probe: ${PROBES[probe.id].label}`);
  };
  const sendBody = request.bodyMissing || (editing && body !== (capturedBody ?? ''));
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const chosen = targets.find((t) => t.name === target);
  const edited = editing ? requestEdits(captured, draft) : { edits: {}, changed: [] };
  const editError = 'error' in edited ? edited.error : undefined;
  const edits = 'error' in edited ? {} : edited.edits;
  const changedParts = [...('error' in edited ? [] : edited.changed), ...(editing && sendBody ? ['body'] : [])];
  // Values hidden by redact-keys are shown, and would be sent, as the text [REDACTED].
  const sendsRedacted =
    editing && [draft.query, draft.headers, sendBody ? body : ''].some((value) => value.includes('[REDACTED]'));
  const blocked =
    editError !== undefined ||
    (request.queryMissing && edits.query === undefined) ||
    (sendBody && body.trim() === '') ||
    (request.unsafe && !confirmed) ||
    (pausing && breakpoints.length === 0);
  const canPause = target === 'local' && steps.length > 0;
  const presetVerdict =
    phase.kind === 'done' && preset && !isProbe(preset.id) ? verdict(preset.id, phase.outcome.httpStatus) : undefined;

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
          { traceId, spanId: request.spanId, confirm: confirmed, body: sendBody ? body : undefined, breakpoints, ...edits },
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
        { traceId, spanId: request.spanId, target, confirm: confirmed, body: sendBody ? body : undefined, ...edits },
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

      <label className="flex items-center gap-2 text-xs text-ink-2">
        <input
          type="checkbox"
          checked={editing}
          onChange={(e) => setEditing(e.target.checked)}
          className="accent-[var(--signal)]"
        />
        <span>
          Edit the request before sending: path, query, headers{request.hasBody ? ', body' : ''}. Try a fix, a different
          value, or a security case (
          <button type="button" onClick={onShowGuide} className="text-ink underline decoration-line underline-offset-2 hover:decoration-ink">
            see the security checks
          </button>
          )
        </span>
      </label>
      {editing && filledIn && (
        <p className="text-xs text-server">
          Filled in: {filledIn}. Review the changes below, then replay to see what the security checks find.
        </p>
      )}
      {editing && (
        <div aria-label="Edit request" className="space-y-2">
          {draft.path !== undefined && (
            <label className="flex items-center gap-2">
              <span className="w-20 shrink-0 text-xs text-muted">Path</span>
              <input
                aria-label="Path to send"
                value={draft.path}
                onChange={(e) => setDraft({ ...draft, path: e.target.value })}
                spellCheck={false}
                className="w-full rounded-lg border border-line bg-paper-2 px-2 py-1 font-mono text-[12px] text-ink focus:border-ink/50 focus:outline-none"
              />
            </label>
          )}
          <label className="flex items-center gap-2">
            <span className="w-20 shrink-0 text-xs text-muted">Query</span>
            <input
              aria-label="Query to send"
              value={draft.query}
              placeholder="none"
              onChange={(e) => setDraft({ ...draft, query: e.target.value })}
              spellCheck={false}
              className="w-full rounded-lg border border-line bg-paper-2 px-2 py-1 font-mono text-[12px] text-ink focus:border-ink/50 focus:outline-none"
            />
          </label>
          <label className="block">
            <span className="text-xs text-muted">
              Headers, one <code>Name: value</code> per line. Delete a line to leave that header out.
            </span>
            <textarea
              aria-label="Headers to send"
              value={draft.headers}
              onChange={(e) => setDraft({ ...draft, headers: e.target.value })}
              rows={Math.min(10, Math.max(3, draft.headers.split('\n').length + 1))}
              spellCheck={false}
              className="mt-1 w-full rounded-lg border border-line bg-paper-2 p-3 font-mono text-[12px] text-ink focus:border-ink/50 focus:outline-none"
            />
          </label>
          <div aria-label="Security probe" className="space-y-2 rounded-lg border border-error/30 bg-error/5 p-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="eyebrow text-error">Active probe</span>
              <select
                aria-label="Probe"
                value={probe?.id ?? ''}
                onChange={(e) => {
                  const id = e.target.value;
                  setProbe(isProbe(id) ? { id, params: defaultParams(id) } : undefined);
                  setProbeError(undefined);
                }}
                className="rounded-lg border border-line bg-paper-2 px-2 py-1 text-[12px] text-ink"
              >
                <option value="">None</option>
                {PROBE_IDS.map((id) => (
                  <option key={id} value={id}>
                    {PROBES[id].label}
                  </option>
                ))}
              </select>
              <button type="button" onClick={onShowGuide} className="text-xs text-muted underline underline-offset-2 hover:text-ink">
                what is this?
              </button>
            </div>
            {probe && (
              <>
                <p className="text-xs text-ink-2">{PROBES[probe.id].summary}</p>
                {PROBES[probe.id].fields.map((field) => (
                  <label key={field.key} className="flex items-center gap-2">
                    <span className="w-20 shrink-0 text-xs text-muted">{field.label}</span>
                    <input
                      aria-label={`Probe ${field.label}`}
                      value={probe.params[field.key] ?? ''}
                      placeholder={field.placeholder}
                      onChange={(e) => setProbe({ ...probe, params: { ...probe.params, [field.key]: e.target.value } })}
                      spellCheck={false}
                      className="w-full rounded-lg border border-line bg-paper-2 px-2 py-1 font-mono text-[12px] text-ink focus:border-ink/50 focus:outline-none"
                    />
                  </label>
                ))}
                {PROBES[probe.id].needsBody && !request.hasBody && (
                  <p className="text-xs text-warn">This request has no body, so there is nothing to add a field to.</p>
                )}
                <div className="flex flex-wrap items-center gap-2">
                  <button
                    type="button"
                    onClick={applyCurrentProbe}
                    disabled={paramsError(probe.id, probe.params) !== undefined}
                    className="btn disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    Apply to the request
                  </button>
                  <span className="text-xs text-muted">One request, sent only when you press Replay now.</span>
                </div>
                {probeError && (
                  <p role="alert" className="text-xs text-error">
                    {probeError}
                  </p>
                )}
              </>
            )}
          </div>
          {editError && (
            <p role="alert" className="text-xs text-error">
              {editError}
            </p>
          )}
          {sendsRedacted && (
            <p className="text-xs text-warn">
              Values shown as [REDACTED] (<code>causeline.capture.redact-keys</code>) will be sent as that text.
            </p>
          )}
        </div>
      )}
      {(request.bodyMissing || (editing && request.hasBody)) && (
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
          {changedParts.length > 0 && (
            <p className="text-xs text-server">Sent with your changes to the {changedParts.join(', ')}.</p>
          )}
          {phase.edited && phase.edited.length > 0 && (
            <p className="text-xs text-server">Ran with changed arguments at {phase.edited.join(', ')}.</p>
          )}
          {phase.paused && (
            <p className="text-xs text-muted">
              The replay's timings include the time it spent paused, so the steps from the pause onwards look slower.
            </p>
          )}
          {presetVerdict && <VerdictLine verdict={presetVerdict} />}
          {applied && (
            <ProbeResult
              applied={applied}
              outcome={phase.outcome}
              original={original}
              token={token}
              local={target === 'local'}
            />
          )}
          <ReplayResult outcome={phase.outcome} comparison={phase.comparison} token={token} />
          {chosen?.comparable && (
            <ReplayFindings replayTraceId={phase.outcome.replayTraceId} token={token} onOpenTrace={onOpenTrace} />
          )}
        </>
      )}
    </div>
  );
}

const VERDICT_STYLE: Record<ProbeVerdict['tone'], string> = {
  bad: 'border-error/40 bg-error/10 text-ink',
  good: 'border-ok/40 bg-ok/10 text-ink-2',
  info: 'border-line bg-paper-2 text-ink-2',
};

function VerdictLine({ verdict: result }: { verdict: ProbeVerdict }) {
  return (
    <p role="status" className={`rounded-lg border p-2.5 text-xs ${VERDICT_STYLE[result.tone]}`}>
      {result.text}
    </p>
  );
}

/**
 * What an active probe revealed. The replay's own trace holds the evidence (its response and the
 * SQL it ran), so this reads it from the local store: only a replay to this application has one.
 */
function ProbeResult({
  applied,
  outcome,
  original,
  token,
  local,
}: {
  applied: { id: ProbeId; params: ProbeParams };
  outcome: ReplayOutcome;
  original: OriginalResponse;
  token: string | undefined;
  local: boolean;
}) {
  const [result, setResult] = useState<ProbeVerdict>();

  useEffect(() => {
    if (!local) {
      return undefined;
    }
    let cancelled = false;
    void (async () => {
      // The replay's spans arrive a moment after its response does.
      for (let attempt = 0; attempt < 6 && !cancelled; attempt++) {
        try {
          const view = await fetchTrace(outcome.replayTraceId, token);
          if (!cancelled) {
            setResult(probeVerdict(applied.id, applied.params, outcome.httpStatus, original, summarizeReplay(view)));
          }
          return;
        } catch {
          await new Promise((resolve) => setTimeout(resolve, 400));
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [applied, outcome, original, token, local]);

  if (!local) {
    return (
      <VerdictLine
        verdict={{
          tone: 'info',
          text: `Probe "${PROBES[applied.id].label}" answered HTTP ${outcome.httpStatus}. A verdict needs the replay's trace, which only a replay to this application (local) has.`,
        }}
      />
    );
  }
  return result ? <VerdictLine verdict={result} /> : <p className="text-xs text-muted">Reading the probe's trace…</p>;
}

/** What the security checks found on the replay's own trace, so the developer needn't go looking. */
function ReplayFindings({
  replayTraceId,
  token,
  onOpenTrace,
}: {
  replayTraceId: string;
  token: string | undefined;
  onOpenTrace?: (traceId: string) => void;
}) {
  const [findings, setFindings] = useState<Insight[]>();

  useEffect(() => {
    let cancelled = false;
    fetchTrace(replayTraceId, token)
      .then((view) => !cancelled && setFindings(view.insights.filter((i) => isSecurityRule(i.rule))))
      .catch(() => !cancelled && setFindings(undefined));
    return () => {
      cancelled = true;
    };
  }, [replayTraceId, token]);

  if (!findings) {
    return null;
  }
  return <SecurityFindings findings={findings} onOpen={onOpenTrace ? () => onOpenTrace(replayTraceId) : undefined} />;
}

export function SecurityFindings({ findings, onOpen }: { findings: Insight[]; onOpen?: () => void }) {
  return (
    <div aria-label="Security checks on the replay" className="rounded-lg border border-line-soft p-3 text-xs">
      <div className="flex items-center gap-2">
        <span className="eyebrow">Security checks on the replay</span>
        {onOpen && (
          <button type="button" onClick={onOpen} className="ml-auto text-muted hover:text-ink">
            Open the replay's trace →
          </button>
        )}
      </div>
      {findings.length === 0 ? (
        <p className="mt-2 text-ink-2">Nothing found. That's a hint, not a guarantee.</p>
      ) : (
        <ul className="mt-2 space-y-1.5">
          {findings.map((finding) => (
            <li key={finding.rule + finding.spanId} className="flex items-start gap-2 text-ink-2">
              <span className="chip shrink-0 border-error/40 font-mono text-error">{SECURITY_RULES[finding.rule]}</span>
              <span>{finding.label}</span>
            </li>
          ))}
        </ul>
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

export function ReplayResult({
  outcome,
  comparison,
  token,
}: {
  outcome: ReplayOutcome;
  comparison: Comparison | undefined;
  token?: string;
}) {
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
      {result && <ResponseChanges result={result} token={token} left="Original" right="Replay" />}
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

const FIELD_STYLE = { added: 'text-ok', removed: 'text-error', changed: 'text-ink' } as const;

/**
 * What changed in the response bodies between the two runs, field by field. Shows nothing until
 * both traces are loaded, and nothing at all when one of them isn't in this application's store
 * (a replay on a remote target).
 */
export function ResponseChanges({
  result,
  token,
  left,
  right,
}: {
  result: ComparisonResult;
  token: string | undefined;
  left: string;
  right: string;
}) {
  const [diffs, setDiffs] = useState<ResponseDiff[]>();
  const before = result.original.traceId;
  const after = result.replay.traceId;
  useEffect(() => {
    let cancelled = false;
    setDiffs(undefined);
    Promise.all([fetchTrace(before, token), fetchTrace(after, token)])
      .then(([a, b]) => !cancelled && setDiffs(responseDiffs(a, b)))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [before, after, token]);
  return diffs && diffs.length > 0 ? <ResponseDiffList diffs={diffs} left={left} right={right} /> : null;
}

export function ResponseDiffList({ diffs, left, right }: { diffs: ResponseDiff[]; left: string; right: string }) {
  return (
    <div className="mt-3 space-y-2 text-xs" aria-label="Response body changes">
      {diffs.map((diff, i) => (
        <div key={i}>
          <p className="font-medium text-ink-2">
            Response of {diff.request}:{' '}
            {diff.note ? (
              <span className="font-normal text-muted">{diff.note}</span>
            ) : diff.changes.length === 0 ? (
              <span className="font-normal text-muted">same body in both runs</span>
            ) : (
              <span className="font-normal text-muted">
                {diff.changes.length} {diff.changes.length === 1 ? 'field differs' : 'fields differ'}
              </span>
            )}
          </p>
          {diff.changes.length > 0 && (
            <table className="mt-1 w-full table-fixed">
              <thead>
                <tr className="text-left text-muted">
                  <th className="w-1/3 py-1 font-medium">Field</th>
                  <th className="py-1 font-medium">{left}</th>
                  <th className="py-1 font-medium">{right}</th>
                </tr>
              </thead>
              <tbody>
                {diff.changes.map((change) => (
                  <tr key={change.path} className={`border-t border-line-soft ${FIELD_STYLE[change.kind]}`}>
                    <td className="truncate py-1 font-mono" title={change.path}>
                      {change.path || '(whole body)'}
                    </td>
                    <td className="truncate py-1 font-mono" title={change.before}>
                      {change.before ?? '—'}
                    </td>
                    <td className="truncate py-1 font-mono" title={change.after}>
                      {change.after ?? '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      ))}
    </div>
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
