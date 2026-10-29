// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef, useState } from 'react';
import { fetchMcpInfo } from './api';
import type { McpInfo, Onboarding, TraceSummary } from './api';
import { formatClock } from './format';

const TOKEN_HEADER = 'X-Causeline-Token';
const TOKEN_PLACEHOLDER = '<token from the Causeline UI: link in your log>';

function appOrigin(): string {
  return typeof window === 'undefined' ? 'http://localhost:8080' : window.location.origin;
}

/** The command that connects Claude Code, as the application log prints it. */
export function claudeCommand(origin: string, path: string, token: string | undefined): string {
  return `claude mcp add --transport http causeline ${origin}${path} --header "${TOKEN_HEADER}: ${token ?? TOKEN_PLACEHOLDER}"`;
}

/** The server entry most other MCP clients (Cursor, Windsurf, VS Code) take in their JSON settings. */
export function clientConfig(origin: string, path: string, token: string | undefined): string {
  return JSON.stringify(
    { mcpServers: { causeline: { url: `${origin}${path}`, headers: { [TOKEN_HEADER]: token ?? TOKEN_PLACEHOLDER } } } },
    null,
    2,
  );
}

/** One line per rule the developer set, in plain words, for the "What it may replay" list. */
export function replayRules(info: McpInfo): string[] {
  if (!info.replayAvailable) {
    return ['Replay is not available in this application.'];
  }
  const rules = [
    info.safeMethodsOnly
      ? 'Only GET and HEAD requests. List anything else in causeline.mcp.replay.allow.'
      : `Only: ${info.allow.join(', ')}`,
  ];
  if (info.deny.length > 0) {
    rules.push(`Never: ${info.deny.join(', ')}`);
  }
  const targets = info.targets.filter((t) => t.allowed).map((t) => t.name);
  rules.push(`To: ${targets.join(', ')}. Other targets need allow-mcp: true.`);
  rules.push(`At most ${info.maxReplaysPerMinute} replays a minute. Requests with side effects need its confirmation.`);
  return rules;
}

/** "Connected" once an agent has called a tool; the count resets when the application restarts. */
export function agentState(onboarding: Onboarding | undefined): { connected: boolean; label: string } {
  const calls = onboarding?.agentCalls ?? 0;
  if (calls === 0) {
    return { connected: false, label: 'No agent has called yet' };
  }
  const last = onboarding?.lastAgentCallAt;
  return {
    connected: true,
    label: `${calls} tool ${calls === 1 ? 'call' : 'calls'}${last ? `, last at ${new Date(last).toLocaleTimeString()}` : ''}`,
  };
}

/** The header button that opens the panel; shows whether an agent is using Causeline. */
export function AgentButton({ onboarding, onClick }: { onboarding: Onboarding | undefined; onClick: () => void }) {
  const state = agentState(onboarding);
  return (
    <button
      type="button"
      onClick={onClick}
      className="btn"
      title={state.connected ? `Coding agent connected over MCP: ${state.label}` : 'Let your coding agent read traces over MCP'}
    >
      <span aria-hidden className={`h-2 w-2 rounded-full ${state.connected ? 'bg-ok' : 'border border-muted'}`} />
      <span className="hidden sm:inline">{state.connected ? 'Agent connected' : 'Connect agent'}</span>
      <span className="sm:hidden">Agent</span>
    </button>
  );
}

/**
 * Tells the developer that their coding agent can use Causeline over MCP, how to connect it, and
 * exactly what it may see and replay. A native dialog: Escape closes it.
 */
export function AgentDialog({
  open,
  onClose,
  token,
  onboarding,
  traces,
  onOpenTrace,
}: {
  open: boolean;
  onClose: () => void;
  token: string | undefined;
  onboarding: Onboarding | undefined;
  traces: TraceSummary[];
  onOpenTrace: (traceId: string) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [info, setInfo] = useState<McpInfo | null>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    const element = dialog.current;
    if (!element) {
      return;
    }
    if (open && !element.open) {
      if (typeof element.showModal === 'function') {
        element.showModal();
      } else {
        element.setAttribute('open', '');
      }
    } else if (!open && element.open) {
      element.close();
    }
  }, [open]);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    let cancelled = false;
    fetchMcpInfo(token)
      .then((result) => !cancelled && setInfo(result))
      .catch((e: unknown) => !cancelled && setError(e instanceof Error ? e.message : String(e)));
    return () => {
      cancelled = true;
    };
  }, [open, token]);

  return (
    <dialog
      ref={dialog}
      aria-labelledby="agent-title"
      onClose={onClose}
      onClick={(e) => e.target === e.currentTarget && onClose()}
      className="panel m-auto max-h-[85vh] w-[min(680px,calc(100vw-32px))] overflow-y-auto bg-card p-0 text-ink backdrop:bg-black/60"
    >
      <AgentContent
        info={info}
        error={error}
        token={token}
        onboarding={onboarding}
        traces={traces}
        onClose={onClose}
        onOpenTrace={(traceId) => {
          onOpenTrace(traceId);
          onClose();
        }}
      />
    </dialog>
  );
}

/** The panel's content; separate from the dialog so it can be rendered in tests. */
export function AgentContent({
  info,
  error,
  token,
  onboarding,
  traces,
  onClose,
  onOpenTrace,
}: {
  /** undefined while loading, null when MCP is switched off */
  info: McpInfo | null | undefined;
  error?: string;
  token: string | undefined;
  onboarding: Onboarding | undefined;
  traces: TraceSummary[];
  onClose: () => void;
  onOpenTrace: (traceId: string) => void;
}) {
  const [client, setClient] = useState<'claude' | 'other'>('claude');
  const state = agentState(onboarding);
  const agentReplays = traces.filter((t) => t.byAgent).slice(0, 5);
  const path = info?.path ?? '/causeline/mcp';

  return (
    <div className="p-5 sm:p-6">
      <div className="flex items-start gap-3">
        <div>
          <p className="eyebrow">MCP · Model Context Protocol</p>
          <h2 id="agent-title" className="mt-1 font-serif text-3xl text-ink">
            Your coding agent can use Causeline
          </h2>
        </div>
        <button type="button" onClick={onClose} aria-label="Close" className="btn ml-auto px-2.5">
          {'✕'}
        </button>
      </div>

      <p className="mt-3 text-sm text-ink-2">
        Claude Code, Cursor, Copilot and other MCP clients can read the traces shown here: what failed, where it was
        thrown, which span is slow. After a fix they can replay the original request and compare the two runs.
        Causeline runs no AI itself; it answers your agent.
      </p>

      <p className="mt-3 flex items-center gap-2 text-[13px]" role="status">
        <span aria-hidden className={`h-2 w-2 rounded-full ${state.connected ? 'bg-ok' : 'border border-muted'}`} />
        <span className={state.connected ? 'text-ok' : 'text-muted'}>{state.label}</span>
      </p>

      {error && (
        <p role="alert" className="mt-4 text-sm text-error">
          Couldn&rsquo;t load the agent settings: {error}
        </p>
      )}

      {info === null ? (
        <p className="panel mt-5 p-4 text-sm text-ink-2">
          Agent access is switched off in this application (<code className="text-ink">causeline.mcp.enabled=false</code>).
        </p>
      ) : (
        <>
          <section aria-labelledby="agent-connect" className="mt-6">
            <h3 id="agent-connect" className="text-sm font-semibold text-ink">
              Connect
            </h3>
            <div role="tablist" aria-label="MCP client" className="mt-2 flex gap-1.5">
              {(
                [
                  ['claude', 'Claude Code'],
                  ['other', 'Cursor and others'],
                ] as const
              ).map(([id, label]) => (
                <button
                  key={id}
                  type="button"
                  role="tab"
                  aria-selected={client === id}
                  onClick={() => setClient(id)}
                  className={`chip ${client === id ? 'border-ink/40 text-ink' : 'text-muted hover:text-ink'}`}
                >
                  {label}
                </button>
              ))}
            </div>
            <SecretCopy
              text={client === 'claude' ? claudeCommand(appOrigin(), path, token) : clientConfig(appOrigin(), path, token)}
              secret={token}
            />
            <p className="mt-2 text-xs text-muted">
              The token is this UI&rsquo;s access token. It changes when the application restarts unless you set{' '}
              <code className="text-ink-2">causeline.access-token</code>.
            </p>
          </section>

          <section aria-labelledby="agent-sees" className="mt-6">
            <h3 id="agent-sees" className="text-sm font-semibold text-ink">
              What it sees
            </h3>
            <p className="mt-1 text-[13px] text-ink-2">
              Traces as an export shows them: credentials, sensitive values and SQL literals are redacted, because an
              agent&rsquo;s context usually goes to a model provider. Long values are cut.
            </p>
            {info && info.hide.length > 0 && (
              <p className="mt-1 text-[13px] text-ink-2">
                Hidden from the agent: <Patterns patterns={info.hide} />
              </p>
            )}
          </section>

          <section aria-labelledby="agent-replays" className="mt-6">
            <h3 id="agent-replays" className="text-sm font-semibold text-ink">
              What it may replay
            </h3>
            {info ? (
              <ul className="mt-1 list-disc space-y-0.5 pl-5 text-[13px] text-ink-2">
                {replayRules(info).map((rule) => (
                  <li key={rule}>{rule}</li>
                ))}
                <li>It can&rsquo;t change headers, and it never sees the credentials a replay resends.</li>
              </ul>
            ) : (
              <p className="mt-1 text-[13px] text-muted">Loading…</p>
            )}
            <p className="mt-2 text-xs text-muted">
              Set these in your application&rsquo;s configuration under <code className="text-ink-2">causeline.mcp.*</code>;
              your agent can&rsquo;t change them.
            </p>
          </section>

          {info && (
            <section aria-labelledby="agent-tools" className="mt-6">
              <h3 id="agent-tools" className="text-sm font-semibold text-ink">
                Tools
              </h3>
              <ul className="mt-2 grid gap-1.5 sm:grid-cols-2">
                {info.tools.map((tool) => (
                  <li key={tool.name} className="rounded-lg border border-line px-3 py-2" title={tool.description}>
                    <div className="flex items-center gap-2">
                      <code className="text-[12px] text-ink">{tool.name}</code>
                      {!tool.readOnly && <span className="chip border-warn/40 text-warn">sends requests</span>}
                    </div>
                    <p className="mt-0.5 line-clamp-2 text-xs text-muted">{firstSentence(tool.description)}</p>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {agentReplays.length > 0 && (
            <section aria-labelledby="agent-recent" className="mt-6">
              <h3 id="agent-recent" className="text-sm font-semibold text-ink">
                Replayed by your agent
              </h3>
              <ul className="mt-2 space-y-1">
                {agentReplays.map((t) => (
                  <li key={t.traceId}>
                    <button
                      type="button"
                      onClick={() => onOpenTrace(t.traceId)}
                      className="flex w-full items-center gap-2 rounded-lg px-2 py-1 text-left text-[13px] hover:bg-paper-2"
                    >
                      <span aria-hidden className={`h-2 w-2 rounded-full ${t.status === 'ERROR' ? 'bg-error' : 'bg-ok'}`} />
                      <span className="truncate text-ink">{t.name}</span>
                      <span className="ml-auto font-mono text-[11px] text-muted">{formatClock(t.startTimeUnixNano)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </>
      )}
    </div>
  );
}

function Patterns({ patterns }: { patterns: string[] }) {
  return (
    <>
      {patterns.map((p, i) => (
        <span key={p}>
          {i > 0 && ', '}
          <code className="text-ink">{p}</code>
        </span>
      ))}
    </>
  );
}

/** Up to the first full stop that ends a sentence; "e.g." and "i.e." don't. */
export function firstSentence(text: string): string {
  const end = /(?<!\b(?:e\.g|i\.e))\.\s/.exec(text);
  return end ? text.slice(0, end.index + 1) : text;
}

/** A copyable block with the token masked on screen (it ends up in screenshots); Copy takes it in full. */
function SecretCopy({ text, secret }: { text: string; secret: string | undefined }) {
  const [copied, setCopied] = useState(false);
  const [shown, setShown] = useState(false);
  const display = secret && !shown ? text.split(secret).join('•'.repeat(12)) : text;
  return (
    <div className="relative mt-2 rounded-lg border border-line bg-paper-2">
      <pre className="p-3 pr-28 text-[12px] leading-relaxed break-all whitespace-pre-wrap text-ink-2">{display}</pre>
      <div className="absolute top-2 right-2 flex gap-1">
        {secret && (
          <button
            type="button"
            onClick={() => setShown((s) => !s)}
            className="rounded-md border border-line bg-card px-2 py-0.5 font-mono text-[10px] tracking-wider text-muted uppercase hover:text-ink"
          >
            {shown ? 'Hide' : 'Show'}
          </button>
        )}
        <button
          type="button"
          onClick={() =>
            void navigator.clipboard?.writeText(text).then(() => {
              setCopied(true);
              setTimeout(() => setCopied(false), 1400);
            })
          }
          className="rounded-md border border-line bg-card px-2 py-0.5 font-mono text-[10px] tracking-wider text-muted uppercase hover:text-ink"
        >
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
    </div>
  );
}
