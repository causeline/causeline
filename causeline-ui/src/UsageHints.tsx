// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from 'react';

export interface Hints {
  /** Numbered: the order to do things in. */
  steps: ReactNode[];
  /** Unnumbered: how to read the result, and what to watch out for. */
  tips: ReactNode[];
}

/** A collapsed "How to use" block: out of the way once someone knows the feature. */
export function UsageHints({ label = 'How to use', hints }: { label?: string; hints: Hints }) {
  return (
    <details className="group rounded-lg border border-line-soft bg-paper-2/60 px-3 py-2 text-xs text-ink-2">
      <summary className="cursor-pointer select-none text-muted hover:text-ink">
        <span className="text-server">?</span> {label}
      </summary>
      <ol className="mt-2 list-decimal space-y-1 pl-5">
        {hints.steps.map((step, i) => (
          <li key={i}>{step}</li>
        ))}
      </ol>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-muted">
        {hints.tips.map((tip, i) => (
          <li key={i}>{tip}</li>
        ))}
      </ul>
    </details>
  );
}

export const LOAD_HINTS: Hints = {
  steps: [
    <>
      Make the trace you want to load first: do the action once (click Checkout), then open its trace here.
    </>,
    <>
      Pick <strong>This request</strong> to hammer one endpoint, or <strong>The whole flow</strong> to replay every
      request of the action in order, with the user&rsquo;s pauses, as one virtual user.
    </>,
    <>
      Start small: <strong>20–50 hits, 5–10 in flight</strong>. Tick <strong>Ramp</strong> to see at which concurrency it
      starts to hurt.
    </>,
    <>
      Press <strong>Start load</strong>. 5 hits go one at a time first (the baseline), then the load.
    </>,
    <>
      Read <strong>What grew</strong> from the top: the first highlighted span is the one that slows down under
      concurrency. Open a <strong>kept trace</strong> to see that span in a slow run.
    </>,
    <>
      Fix it, then run the same load again and compare the two results.
    </>,
  ],
  tips: [
    <>
      A span that <em>waits</em> (a lock, a pool, one connection) grows in self time; a span that just
      <em> works</em> stays flat. Growth in a database span usually means locking or the connection pool.
    </>,
    <>
      If <strong>Queries per hit</strong> rises under load, something is skipping a cache or retrying.
    </>,
    <>Exceptions marked <strong>only under concurrency</strong> are races: deadlocks, optimistic locks, unique keys.</>,
    <>
      Everything runs in this application on this machine, so other spans may grow a little too (CPU, garbage
      collection). Trust big, repeatable differences; for capacity numbers use <strong>Copy as k6</strong> from another
      machine.
    </>,
    <>
      Requests that change data really run every time: orders and payments repeat. Prefer <code>local</code> with test
      data; another target must set <code>allow-load: true</code>.
    </>,
  ],
};

/**
 * @param canTry the guide was opened from the replay panel, so its Fill in and Set up probe buttons
 *               exist; elsewhere the hints point to the replay panel instead
 */
export const securityHints = (canTry: boolean): Hints => ({
  steps: [
    <>
      Use the app as normal (or click the demo&rsquo;s <strong>Security test scenarios</strong>). Every captured request
      is checked as it arrives; findings show as red tags on the trace and its spans.
    </>,
    <>
      Look at the <strong>overview</strong> below: findings across all stored traces, grouped by rule, naming the request
      and the field. Open that request&rsquo;s trace from the list to see why it was flagged.
    </>,
    ...(canTry
      ? [
          <>
            To try a case on a request, pick <strong>Fill in</strong> next to a check: the replay form gets the change (a
            token in the URL, no credentials, an unsigned JWT…). Review it and press <strong>Replay now</strong>.
          </>,
          <>
            For access control, use an <strong>active probe</strong> (<strong>Set up probe</strong>): type another
            user&rsquo;s ID, an extra field or a header, apply it, and replay. The verdict says what the replay revealed.
          </>,
        ]
      : [
          <>
            To try a case or an active probe on a request, open its trace and use <strong>Try security checks</strong> in
            the Replay panel: the guide there fills the replay form for you.
          </>,
        ]),
    <>
      Fix the code, replay again, and check the finding is gone. Then <strong>Dismiss</strong> rules that don&rsquo;t
      apply to your app, so the overview only shows what&rsquo;s left.
    </>,
  ],
  tips: [
    <>
      A refusal (401, 403, or a 4xx for an extra field) is the good outcome. A 2xx with someone else&rsquo;s data is the
      finding.
    </>,
    <>
      Verdicts read the replay&rsquo;s trace, so they need a replay to <code>local</code>; other targets show the HTTP
      status only.
    </>,
    <>
      Keep a fixed finding fixed: add <code>TraceAssert.hasNoSecurityFindings()</code> to a test with{' '}
      <code>@CauselineTest</code>.
    </>,
    <>
      These are hints from the requests you made, not a scan: untested endpoints aren&rsquo;t covered, and a clean trace
      doesn&rsquo;t prove the app is safe.
    </>,
  ],
});
