// SPDX-License-Identifier: Apache-2.0
import { useState } from 'react';
import type { ReactNode } from 'react';
import type { Onboarding } from './api';

interface Step {
  id: string;
  title: string;
  done: boolean;
  optional?: boolean;
  /** Shown while the step is still open. */
  hint: ReactNode;
  code?: string;
}

/** The base URL of the host app, for copy-paste examples. Falls back for server rendering. */
function appOrigin(): string {
  return typeof window === 'undefined' ? 'http://localhost:8080' : window.location.origin;
}

export function steps(onboarding: Onboarding | undefined): Step[] {
  const app = onboarding?.appName ?? 'your app';
  return [
    {
      id: 'backend',
      title: `Causeline is running in ${app}`,
      done: true,
      hint: null,
    },
    {
      id: 'server',
      title: 'Server requests are being traced',
      done: (onboarding?.serverSpans ?? 0) > 0,
      hint: 'Call any endpoint of your app: from your front end, Postman or the command line.',
      code: `curl ${appOrigin()}/api/your-endpoint`,
    },
    {
      id: 'browser',
      title: 'The React SDK is connected',
      done: (onboarding?.browserSpans ?? 0) > 0,
      hint: (
        <>
          Wrap your app in the provider, then use the page. Spans arrive within a second. If the React dev server runs
          on another port, proxy <code className="text-ink">/causeline</code> to this app.
        </>
      ),
      code: `<CauselineProvider endpoint="/causeline/api/spans">\n  <App />\n</CauselineProvider>`,
    },
    {
      id: 'actions',
      title: 'Clicks are named with trace()',
      done: (onboarding?.namedActions ?? 0) > 0,
      optional: true,
      hint: 'Optional, but it turns "POST /api/orders" into "Checkout": the click, the request and the state update in one trace.',
      code: `const onCheckout = () =>\n  trace('Checkout', () => fetch('/api/orders', { method: 'POST' }));`,
    },
  ];
}

/** First-run screen: what is connected, what is missing, and how to fix it. */
export function EmptyState({ onboarding }: { onboarding: Onboarding | undefined }) {
  const list = steps(onboarding);
  const required = list.filter((s) => !s.optional);
  const done = required.filter((s) => s.done).length;
  const next = list.find((s) => !s.done);

  return (
    <section aria-label="Getting started" className="relative isolate overflow-hidden px-6 py-12 md:px-12 md:py-16">
      <div aria-hidden className="grid-paper absolute inset-0 -z-10 [mask-image:radial-gradient(ellipse_70%_60%_at_50%_0%,#000_30%,transparent_75%)]" />
      <div className="mx-auto max-w-2xl">
        <div className="fade-up flex items-center gap-3">
          <Radar />
          <span className="eyebrow">Listening on {onboarding?.appName ?? 'your app'}</span>
        </div>
        <h2 className="fade-up mt-5 font-serif text-5xl leading-[.95] tracking-tight [animation-delay:80ms] md:text-6xl">
          Waiting for your <em className="text-signal">first</em> request.
        </h2>
        <p className="fade-up mt-4 max-w-xl text-[15px] text-ink-2 [animation-delay:160ms]">
          No traces yet. Use your app with Causeline enabled; traces appear here within a second or two. This
          checklist updates live.
        </p>

        <div className="fade-up mt-8 [animation-delay:220ms]">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>
              {done} of {required.length} connected
            </span>
            {done === required.length && <span className="text-ok">All set: make a request</span>}
          </div>
          <div className="mt-2 h-1 overflow-hidden rounded-full bg-line-soft">
            <div
              className="h-full rounded-full bg-signal transition-[width] duration-700 ease-[var(--ease-out-expo)]"
              style={{ width: `${(done / required.length) * 100}%` }}
            />
          </div>
        </div>

        <ol className="mt-6 space-y-3">
          {list.map((step, i) => (
            <StepRow key={step.id} step={step} index={i} active={step === next} />
          ))}
        </ol>

        <p className="fade-up mt-8 text-[13px] text-muted [animation-delay:500ms]">
          Stuck? The{' '}
          <a
            className="text-ink-2 underline decoration-line underline-offset-4 hover:text-signal hover:decoration-signal"
            href="https://github.com/causeline/causeline/blob/main/docs/QUICKSTART.md"
            target="_blank"
            rel="noreferrer"
          >
            quick start
          </a>{' '}
          has a troubleshooting table.
        </p>
      </div>
    </section>
  );
}

function StepRow({ step, index, active }: { step: Step; index: number; active: boolean }) {
  return (
    <li
      className={`fade-up panel p-4 transition-colors ${active ? 'border-ink/40' : ''}`}
      style={{ animationDelay: `${260 + index * 70}ms` }}
      data-done={step.done}
    >
      <div className="flex items-start gap-3">
        <StatusIcon done={step.done} active={active} />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className={`font-medium ${step.done ? 'text-ink' : 'text-ink-2'}`}>{step.title}</span>
            {step.optional && <span className="chip text-muted">optional</span>}
            <span className="sr-only">{step.done ? 'done' : 'not yet'}</span>
          </div>
          {!step.done && step.hint && <p className="mt-1 text-[13px] text-muted">{step.hint}</p>}
          {!step.done && step.code && <CodeLine code={step.code} />}
        </div>
      </div>
    </li>
  );
}

function StatusIcon({ done, active }: { done: boolean; active: boolean }) {
  if (done) {
    return (
      <span className="mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full bg-ok text-paper" aria-hidden>
        <svg width="11" height="11" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M2.5 6.5l2.2 2.2 4.8-5" />
        </svg>
      </span>
    );
  }
  return (
    <span aria-hidden className="relative mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full border border-line">
      {active && (
        <>
          <span className="absolute h-2 w-2 rounded-full bg-signal" />
          <span className="absolute h-2 w-2 animate-[ping-soft_1.8s_var(--ease-out-expo)_infinite] rounded-full bg-signal" />
        </>
      )}
    </span>
  );
}

function CodeLine({ code }: { code: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="group relative mt-3 rounded-lg border border-line bg-paper-2">
      <pre className="p-3 pr-16 text-[12px] leading-relaxed break-words whitespace-pre-wrap text-ink-2">{code}</pre>
      <button
        type="button"
        onClick={() =>
          void navigator.clipboard?.writeText(code).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 1400);
          })
        }
        className="absolute top-2 right-2 rounded-md border border-line bg-card px-2 py-0.5 font-mono text-[10px] tracking-wider text-muted uppercase hover:text-ink"
      >
        {copied ? 'Copied' : 'Copy'}
      </button>
    </div>
  );
}

/** A small radar: the UI is listening for spans. */
function Radar() {
  return (
    <span aria-hidden className="relative grid h-7 w-7 place-items-center">
      <span className="absolute inset-0 animate-[ping-soft_2.4s_var(--ease-out-expo)_infinite] rounded-full border border-signal/60" />
      <span className="absolute inset-1.5 rounded-full border border-signal/40" />
      <span className="h-1.5 w-1.5 rounded-full bg-signal" />
    </span>
  );
}
