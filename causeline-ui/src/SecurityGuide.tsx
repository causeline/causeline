// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef, useState } from 'react';
import { dismissSecurityRule, fetchSecurityOverview } from './api';
import type { InsightRule, SecurityOverview } from './api';
import { PROBES } from './probes';
import type { ProbeId } from './probes';
import type { RequestDraft } from './ReplayPanel';
import { UsageHints, securityHints } from './UsageHints';

/** Insight rules that are security checks (PRD section 7), each with its OWASP Top 10:2025 category. */
export const SECURITY_RULES: Partial<Record<InsightRule, string>> = {
  REQUEST_VALUE_IN_SQL: 'A05',
  MISSING_SECURITY_HEADERS: 'A02',
  SERVER_VERSION_DISCLOSED: 'A02',
  PERMISSIVE_CORS: 'A02',
  INSECURE_SESSION_COOKIE: 'A07',
  WEAK_JWT: 'A07',
  SECRET_IN_URL: 'A04',
  ERROR_DETAILS_EXPOSED: 'A10',
  SECRET_IN_LOG: 'A09',
  SENSITIVE_FIELD_IN_RESPONSE: 'A04',
  REQUEST_VALUE_IN_OUTBOUND_URL: 'A01',
};

export const isSecurityRule = (rule: InsightRule) => rule in SECURITY_RULES;

export type PresetId = 'token-in-url' | 'unsigned-jwt' | 'no-credentials' | 'malformed-body';

/** What the guide can set up in the replay form: a ready-made change, or a probe that needs your input. */
export type TryId = PresetId | ProbeId;

/**
 * A plain verdict after a replay of a security case: what the HTTP status says about the app. For
 * "no-credentials" and "unsigned-jwt" a 2xx is itself the finding (the app accepted the request).
 */
export function replayVerdict(preset: PresetId, httpStatus: number): { tone: 'bad' | 'good'; text: string } | undefined {
  const ok = httpStatus >= 200 && httpStatus < 300;
  const unauthorized = httpStatus === 401 || httpStatus === 403;
  if (preset === 'no-credentials') {
    if (ok) {
      return { tone: 'bad', text: `Answered ${httpStatus} without credentials: this endpoint does not check who is calling (broken access control, A01).` };
    }
    if (unauthorized) {
      return { tone: 'good', text: `Rejected with ${httpStatus} without credentials — the endpoint requires authentication, as it should.` };
    }
  }
  if (preset === 'unsigned-jwt') {
    if (ok) {
      return { tone: 'bad', text: `Answered ${httpStatus} with an unsigned token: your app accepted a forged JWT (A07). Reject tokens with alg "none".` };
    }
    if (unauthorized) {
      return { tone: 'good', text: `Rejected the unsigned token with ${httpStatus} — the signature is being checked.` };
    }
  }
  return undefined;
}

/** A request plus its body, as the replay form edits them. */
export interface Editable {
  draft: RequestDraft;
  body: string;
}

const TEST_TOKEN = 'causeline-test';
/** {"alg":"none"}.{"sub":"causeline-test"}. — unsigned and without an expiry, on purpose. */
const UNSIGNED_JWT = 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJjYXVzZWxpbmUtdGVzdCJ9.';
/** The same headers Causeline treats as credentials on the server (SensitiveData.isCredentialHeader). */
const CREDENTIAL_HEADERS = new Set(['authorization', 'proxy-authorization', 'cookie', 'set-cookie', 'x-api-key', 'api-key']);
const CREDENTIAL_WORDS = /token|secret|password|session|auth/i;

const headerName = (line: string) => line.slice(0, Math.max(0, line.indexOf(':'))).trim().toLowerCase();
const isCredential = (name: string) => CREDENTIAL_HEADERS.has(name) || CREDENTIAL_WORDS.test(name);
const withoutLines = (headers: string, drop: (name: string) => boolean) =>
  headers
    .split('\n')
    .filter((line) => line.trim() !== '' && !drop(headerName(line)))
    .join('\n');

/** Ready-made changes for the replay form, one per case a developer can try by editing a request. */
export const PRESETS: Record<PresetId, { label: string; apply: (current: Editable) => Editable }> = {
  'token-in-url': {
    label: 'Token in the URL',
    apply: ({ draft, body }) => ({
      draft: { ...draft, query: draft.query ? `${draft.query}&access_token=${TEST_TOKEN}` : `access_token=${TEST_TOKEN}` },
      body,
    }),
  },
  'unsigned-jwt': {
    label: 'Unsigned JWT',
    apply: ({ draft, body }) => ({
      draft: {
        ...draft,
        headers: [withoutLines(draft.headers, (name) => name === 'authorization'), `Authorization: Bearer ${UNSIGNED_JWT}`]
          .filter((part) => part !== '')
          .join('\n'),
      },
      body,
    }),
  },
  'no-credentials': {
    label: 'Without credentials',
    apply: ({ draft, body }) => ({ draft: { ...draft, headers: withoutLines(draft.headers, isCredential) }, body }),
  },
  'malformed-body': {
    label: 'Malformed body',
    apply: ({ draft, body }) => ({ draft, body: body.trim().startsWith('{') ? '{"broken": ' : 'broken=' }),
  },
};

interface Check {
  owasp: string;
  title: string;
  /** What Causeline looks for, in the developer's terms. */
  looksFor: string;
  /** Why it matters, in one line. */
  why: string;
  /** The fix. */
  fix: string;
  /** How to see it: a preset to fill in, or a note when every trace is checked anyway. */
  tryIt: { preset: TryId; note: string } | { note: string };
}

const CHECKS: Check[] = [
  {
    owasp: 'A04',
    title: 'Secret in URL',
    looksFor: 'A password, token or API key in a query string, on the server or in the browser.',
    why: 'URLs are kept in server logs, proxy logs and browser history, so the secret is copied everywhere the URL goes.',
    fix: 'Send it in a header or the request body instead.',
    tryIt: { preset: 'token-in-url', note: 'Adds access_token to the query string.' },
  },
  {
    owasp: 'A07',
    title: 'JWT without signature or expiry',
    looksFor: 'A bearer token with alg "none", or without an exp claim.',
    why: 'An unsigned token can be forged by anyone; a token with no expiry stays valid forever once it leaks.',
    fix: 'Reject unsigned tokens, verify the signature, and give every token a short expiry.',
    tryIt: { preset: 'unsigned-jwt', note: 'Sends an unsigned test token. A 2xx reply means your app accepted it.' },
  },
  {
    owasp: 'A01',
    title: 'Works without credentials?',
    looksFor: 'Not a rule: the replay without Authorization and cookies is compared with the original.',
    why: 'If the request still succeeds without credentials, anyone can call the endpoint — broken access control.',
    fix: 'Require authentication on the endpoint, and check the caller is allowed to touch that resource.',
    tryIt: { preset: 'no-credentials', note: 'Removes Authorization, cookies and other credential headers.' },
  },
  {
    owasp: 'A10',
    title: 'Error details sent to the client',
    looksFor: 'A Java stack trace or a SQL error in a response body.',
    why: 'Stack traces and SQL errors reveal your frameworks, table names and internals to an attacker.',
    fix: 'Log the detail on the server and answer with a generic message (server.error.include-stacktrace=never).',
    tryIt: { preset: 'malformed-body', note: 'Sends a broken body, so you see what your error response gives away.' },
  },
  {
    owasp: PROBES.idor.owasp,
    title: 'Another ID (IDOR)',
    looksFor:
      'Active probe: replays the request with a different ID in the path, query or body. A 2xx with a different record means nothing checks who owns each object.',
    why: 'Anyone who can guess or count up IDs can read, or change, other people’s data.',
    fix: 'Check ownership on every object access: compare the record’s owner with the caller, not just that the caller is logged in.',
    tryIt: { preset: 'idor', note: 'You type the other ID. Use one you know belongs to someone else.' },
  },
  {
    owasp: PROBES['mass-assignment'].owasp,
    title: 'Extra field (mass assignment)',
    looksFor:
      'Active probe: replays the request with a field you name added to the JSON body. If its value shows up bound in an INSERT or UPDATE, the client controls a field it should not.',
    why: 'A caller can set role, isAdmin, price or status — fields never meant to be client-writable.',
    fix: 'Bind requests to a DTO that lists only the allowed fields, or whitelist them. Never bind the raw request map or entity.',
    tryIt: { preset: 'mass-assignment', note: 'You type the field name. Needs a JSON body, and causeline.capture.sql=full to see the SQL.' },
  },
  {
    owasp: PROBES['open-redirect'].owasp,
    title: 'Redirect elsewhere (open redirect)',
    looksFor:
      'Active probe: replays the request with a redirect parameter pointing at a test address. If the response redirects there, the app can send users to another site.',
    why: 'Attackers put a trusted domain in front of a link that lands on a phishing page.',
    fix: 'Allow only relative paths, or check the target against an allowlist of hosts.',
    tryIt: { preset: 'open-redirect', note: 'You type the parameter name (next, returnUrl, redirect…). The test address is a reserved .invalid host.' },
  },
  {
    owasp: PROBES['auth-boundary'].owasp,
    title: 'Without a header (auth boundary)',
    looksFor:
      'Active probe: replays the request without a tenant, role or scope header you name. If the answer is the same, the server ignores it.',
    why: 'A header the server doesn’t enforce gives a false sense of access control.',
    fix: 'Derive tenant and role from the authenticated identity on the server, and reject requests that disagree with it.',
    tryIt: { preset: 'auth-boundary', note: 'You type the header name (X-Tenant-Id, X-Role…).' },
  },
  {
    owasp: 'A05',
    title: 'Request value written into SQL',
    looksFor: 'A query, path or body value that appears in the prepared SQL text: concatenated, not bound.',
    why: 'A value built into SQL text instead of bound is how SQL injection happens.',
    fix: 'Pass it as a parameter (?, :name, a repository method argument), never by building the SQL string.',
    tryIt: { note: 'Change a search term or filter value in the query or body, then replay and look for this finding.' },
  },
  {
    owasp: 'A01',
    title: 'Request value steers an outbound call',
    looksFor: 'A request value that appears in the URL of an outbound HTTP call the server then made (SSRF).',
    why: 'A caller who controls that value can make your server fetch an address of their choosing, including internal ones.',
    fix: 'Validate the value against an allowlist of hosts before using it in a server-side request.',
    tryIt: { note: 'If your app calls another service with a value from the request, replay and look for this finding.' },
  },
  {
    owasp: 'A09',
    title: 'Secret written to a log',
    looksFor: 'A credential from the request that appears, as text, in a log line of the same trace.',
    why: 'Logs reach files, aggregators and support tickets, so a logged secret leaks far beyond the app.',
    fix: "Don't log credentials; mask them, or log an identifier instead of the value.",
    tryIt: { note: 'Nothing to do: every log line is checked.' },
  },
  {
    owasp: 'A04',
    title: 'Secret in the response',
    looksFor: 'A response body field whose name says it holds a secret (password, passwordHash, ssn…) with a real value.',
    why: 'A field like this rarely belongs in an API response and is easy to leak to the client by accident.',
    fix: 'Leave it out of the DTO you return.',
    tryIt: { note: 'Nothing to do: every response body is checked.' },
  },
  {
    owasp: 'A02',
    title: 'Missing security headers, server version, CORS',
    looksFor:
      'No X-Content-Type-Options: nosniff (and on HTML, no CSP or frame protection); X-Powered-By or a versioned Server header; any origin allowed with credentials.',
    why: 'Missing headers leave the browser without defences against sniffing, clickjacking and cross-site requests.',
    fix: 'Add the headers (Spring Security sets most by default), and list allowed CORS origins instead of "*".',
    tryIt: { note: 'Nothing to do: every response is checked.' },
  },
  {
    owasp: 'A07',
    title: 'Session cookie without HttpOnly or SameSite',
    looksFor: 'A Set-Cookie for a session or token cookie that a script could read or another site could send.',
    why: 'Without HttpOnly a script can steal the cookie; without SameSite another site can ride on it (CSRF).',
    fix: 'Set HttpOnly and SameSite, and Secure where the site runs on HTTPS.',
    tryIt: { note: 'Nothing to do: every response is checked. Sign in, then look at that trace.' },
  },
];


/**
 * Explains the security checks and, from the replay form, fills in a case to try. A native
 * dialog: Escape closes it and focus stays inside while it is open.
 */
export function SecurityGuide({
  open,
  onClose,
  onTry,
  canEditBody = true,
  token,
}: {
  open: boolean;
  onClose: () => void;
  /** Set where a replay can be edited; without it the guide only explains. */
  onTry?: (preset: TryId) => void;
  canEditBody?: boolean;
  /** When set, the guide loads the across-all-traces overview and lets rules be dismissed. */
  token?: string;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [overview, setOverview] = useState<SecurityOverview>();

  useEffect(() => {
    const element = dialog.current;
    if (!element) {
      return;
    }
    if (open && !element.open) {
      // showModal is missing in some test environments; open as a plain dialog there.
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
    if (!open || token === undefined) {
      return undefined;
    }
    let cancelled = false;
    fetchSecurityOverview(token)
      .then((result) => !cancelled && setOverview(result))
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [open, token]);

  const dismiss = (rule: InsightRule, dismissed: boolean) => {
    if (token === undefined) {
      return;
    }
    dismissSecurityRule(rule, dismissed, token)
      .then(setOverview)
      .catch(() => {});
  };

  return (
    <dialog
      ref={dialog}
      aria-labelledby="security-guide-title"
      onClose={onClose}
      onClick={(e) => e.target === e.currentTarget && onClose()}
      className="panel m-auto max-h-[85vh] w-[min(680px,calc(100vw-32px))] overflow-y-auto bg-card p-0 text-ink backdrop:bg-black/60"
    >
      <div className="p-5 sm:p-6">
        <div className="flex items-start gap-3">
          <div>
            <p className="eyebrow">OWASP Top 10:2025</p>
            <h2 id="security-guide-title" className="mt-1 font-serif text-3xl text-ink">
              Security checks
            </h2>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="btn ml-auto px-2.5">
            {'✕'}
          </button>
        </div>

        <p className="mt-3 text-sm text-ink-2">
          Causeline reads the traffic it already captured and flags common security problems. Because it sees the whole
          trace &mdash; the request, the SQL, the logs, the outbound calls and the response &mdash; it catches things an
          outside scanner can&rsquo;t. Nothing extra is sent, and findings name the request and field, never the captured
          value.
        </p>
        {onTry && (
          <p className="mt-2 text-sm text-ink-2">
            To try a case, pick <strong>Fill in</strong>: the replay form is filled with that change, you review it and
            click <strong>Replay now</strong>. The replay gets its own trace, and the result shows what the checks found.
            The <strong>active probes</strong> need one thing from you (an ID, a field name, a header): <strong>Set up
            probe</strong> opens them in the replay form. Each probe is one request that you send yourself, never a scan.
          </p>
        )}

        <div className="mt-3">
          <UsageHints label="How to use the security checks" hints={securityHints(onTry !== undefined)} />
        </div>

        {overview && <Overview overview={overview} onDismiss={dismiss} />}

        <h3 className="mt-6 text-sm font-semibold text-ink">What it checks</h3>
        <ul className="mt-2 space-y-2">
          {CHECKS.map((check) => {
            const preset = 'preset' in check.tryIt ? check.tryIt.preset : undefined;
            const needsBody = preset === 'malformed-body' || preset === 'mass-assignment';
            const usable = preset !== undefined && onTry && (!needsBody || canEditBody);
            const isProbe = preset !== undefined && preset in PROBES;
            return (
              <li key={check.title} className="rounded-xl border border-line-soft bg-paper-2/60 p-3">
                <div className="flex items-start gap-3">
                  <span className="chip shrink-0 border-error/40 font-mono text-error">{check.owasp}</span>
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-semibold text-ink">{check.title}</p>
                    <p className="mt-0.5 text-xs text-ink-2">{check.looksFor}</p>
                    <p className="mt-1 text-xs text-muted">
                      <span className="text-ink-2">Why:</span> {check.why}
                    </p>
                    <p className="mt-0.5 text-xs text-muted">
                      <span className="text-ok">Fix:</span> {check.fix}
                    </p>
                    <p className="mt-0.5 text-xs italic text-muted">{check.tryIt.note}</p>
                  </div>
                  {usable && (
                    <button
                      type="button"
                      onClick={() => {
                        onTry(preset);
                        onClose();
                      }}
                      className="btn shrink-0"
                    >
                      {isProbe ? 'Set up probe' : 'Fill in'}
                    </button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>

        <p className="mt-4 text-xs text-muted">
          These are hints from single requests, not a security scan: a clean trace doesn&rsquo;t mean the app is safe.
          Edited replays go only to the target you chose, and requests that change data still need your confirmation.
        </p>
      </div>
    </dialog>
  );
}

/** Findings grouped by rule across every stored trace, so there is one place to look. */
function Overview({
  overview,
  onDismiss,
}: {
  overview: SecurityOverview;
  onDismiss: (rule: InsightRule, dismissed: boolean) => void;
}) {
  const total = overview.rules.reduce((sum, group) => sum + group.traces, 0);
  return (
    <div className="mt-4 rounded-xl border border-line bg-paper-2/60 p-3">
      <div className="flex items-baseline gap-2">
        <span className="eyebrow">Across {overview.tracesScanned} traces</span>
        <span className="text-xs text-muted">
          {overview.rules.length === 0
            ? 'no active security findings'
            : total + ' finding' + (total === 1 ? '' : 's') + ' from ' + overview.rules.length + ' rule' + (overview.rules.length === 1 ? '' : 's')}
        </span>
      </div>
      {overview.rules.length > 0 && (
        <ul className="mt-2 space-y-1.5">
          {overview.rules.map((group) => (
            <li key={group.rule} className="flex items-start gap-2 text-xs">
              <span className="chip shrink-0 border-error/40 font-mono text-error">{SECURITY_RULES[group.rule]}</span>
              <span className="min-w-0 flex-1 text-ink-2">
                {group.example}
                <span className="text-muted">
                  {' — ' + group.traces + ' trace' + (group.traces === 1 ? '' : 's') + ', ' + group.endpoints + ' endpoint' + (group.endpoints === 1 ? '' : 's')}
                </span>
              </span>
              <button
                type="button"
                onClick={() => onDismiss(group.rule, true)}
                className="shrink-0 text-muted hover:text-ink"
                title="Hide this rule from the overview for now"
              >
                Dismiss
              </button>
            </li>
          ))}
        </ul>
      )}
      {overview.dismissed.length > 0 && (
        <p className="mt-2 flex flex-wrap items-center gap-2 text-xs text-muted">
          <span>Dismissed:</span>
          {overview.dismissed.map((group) => (
            <button
              key={group.rule}
              type="button"
              onClick={() => onDismiss(group.rule, false)}
              className="chip gap-1 hover:border-ink hover:text-ink"
              title="Show this rule again"
            >
              {SECURITY_RULES[group.rule]} {'↺'}
            </button>
          ))}
        </p>
      )}
    </div>
  );
}

/** Says the checks exist and opens the guide. `prominent` draws it as a primary action. */
export function SecurityGuideButton({
  onClick,
  label = 'Security checks',
  prominent = false,
}: {
  onClick: () => void;
  label?: string;
  prominent?: boolean;
}) {
  const shield = (
    <svg width={prominent ? 13 : 11} height={prominent ? 13 : 11} viewBox="0 0 16 16" aria-hidden>
      <path fill="currentColor" d="M8 1 2 3.5V8c0 3.3 2.6 6.2 6 7 3.4-.8 6-3.7 6-7V3.5L8 1zm0 2.2 4 1.7V8c0 2.2-1.6 4.3-4 5-2.4-.7-4-2.8-4-5V4.9l4-1.7z" />
    </svg>
  );
  if (prominent) {
    return (
      <button
        type="button"
        onClick={onClick}
        className="btn gap-1.5 border-error/50 text-error transition-colors hover:border-error hover:bg-error/10"
      >
        {shield}
        {label}
      </button>
    );
  }
  return (
    <button type="button" onClick={onClick} className="chip gap-1.5 text-ink-2 transition-colors hover:border-ink hover:text-ink">
      {shield}
      {label}
    </button>
  );
}
