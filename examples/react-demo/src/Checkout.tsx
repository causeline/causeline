// SPDX-License-Identifier: Apache-2.0
import { trace, useTracedState } from '@causeline/react';
import { useEffect, useState } from 'react';

type Result = { kind: 'idle' } | { kind: 'ok'; orderId: number } | { kind: 'error'; message: string };

interface Settings {
  slowPayment: boolean;
  failPayment: boolean;
  flakyStock: boolean;
  contendedPayment: boolean;
}

export function Checkout() {
  // useTracedState records *that* these changed during a traced action, never their values.
  const [loading, setLoading] = useTracedState('checkoutLoading', false);
  const [result, setResult] = useTracedState<Result>('checkoutResult', { kind: 'idle' });
  const [settings, setSettings] = useState<Settings>({
    slowPayment: true,
    failPayment: false,
    flakyStock: false,
    contendedPayment: false,
  });

  useEffect(() => {
    void fetch('/api/demo/settings')
      .then((r) => r.json() as Promise<Settings>)
      .then(setSettings)
      .catch(() => {});
  }, []);

  const changeSetting = async (change: Partial<Settings>) => {
    const next = { ...settings, ...change };
    setSettings(next);
    await fetch('/api/demo/settings', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(next),
    });
  };

  const checkout = () =>
    trace('Checkout', async () => {
      setLoading(true);
      try {
        const response = await fetch('/api/orders', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ item: 'book', quantity: 1 }),
        });
        if (!response.ok) {
          setResult({ kind: 'error', message: `HTTP ${response.status}` });
          return;
        }
        const order = (await response.json()) as { orderId: number };
        setResult({ kind: 'ok', orderId: order.orderId });
      } catch (e) {
        setResult({ kind: 'error', message: e instanceof Error ? e.message : String(e) });
      } finally {
        setLoading(false);
      }
    });

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: 32, maxWidth: 560 }}>
      <h1>Checkout demo</h1>
      <fieldset style={{ marginBottom: 16 }}>
        <legend>Payment provider</legend>
        <label style={{ display: 'block' }}>
          <input
            type="checkbox"
            checked={settings.slowPayment}
            onChange={(e) => void changeSetting({ slowPayment: e.target.checked })}
          />{' '}
          Slow (800 ms)
        </label>
        <label style={{ display: 'block' }}>
          <input
            type="checkbox"
            checked={settings.failPayment}
            onChange={(e) => void changeSetting({ failPayment: e.target.checked })}
          />{' '}
          Times out (checkout fails)
        </label>
        <label style={{ display: 'block' }}>
          <input
            type="checkbox"
            checked={settings.contendedPayment}
            onChange={(e) => void changeSetting({ contendedPayment: e.target.checked })}
          />{' '}
          Contended (one charge at a time: fine alone, slow under load; try a load replay)
        </label>
      </fieldset>
      <fieldset style={{ marginBottom: 16 }}>
        <legend>Inventory service</legend>
        <label style={{ display: 'block' }}>
          <input
            type="checkbox"
            checked={settings.flakyStock}
            onChange={(e) => void changeSetting({ flakyStock: e.target.checked })}
          />{' '}
          Flaky (the app catches the error, logs it, and carries on)
        </label>
      </fieldset>
      <button onClick={checkout} disabled={loading}>
        {loading ? 'Placing order…' : 'Checkout'}
      </button>
      {result.kind === 'ok' && <p>Order {result.orderId} placed and paid.</p>}
      {result.kind === 'error' && <p role="alert">Checkout failed: {result.message}</p>}
      <p style={{ color: '#666', fontSize: 14 }}>
        Then open the <code>Causeline UI:</code> link from the backend log to see the trace.
      </p>

      <Assistant />
      <SecurityScenarios />
    </main>
  );
}

/** Each button makes one deliberately-insecure request so a Causeline security check fires on it. */
const SCENARIOS: { label: string; owasp: string; run: () => Promise<Response> }[] = [
  {
    label: 'Request value built into SQL',
    owasp: 'A05',
    run: () => fetch(`/api/scenarios/search?item=${encodeURIComponent('limited-edition-DEMO')}`),
  },
  {
    label: 'Secret returned in the response',
    owasp: 'A04',
    run: () => fetch('/api/scenarios/user'),
  },
  {
    label: 'Credential written to a log',
    owasp: 'A09',
    run: () => fetch('/api/scenarios/login', { headers: { Authorization: 'Bearer demo-secret-token-abc123' } }),
  },
  {
    label: 'Insecure session cookie',
    owasp: 'A07',
    run: () => fetch('/api/scenarios/login'),
  },
  {
    label: 'Server version + permissive CORS',
    owasp: 'A02',
    run: () => fetch('/api/scenarios/legacy'),
  },
  {
    label: 'Request steers an outbound call (SSRF)',
    owasp: 'A01',
    run: () => fetch(`/api/scenarios/fetch?resource=${encodeURIComponent('internal-metadata-service')}`),
  },
  {
    label: 'Stack trace in the response',
    owasp: 'A10',
    run: () => fetch('/api/scenarios/boom'),
  },
  {
    label: 'Secret in the URL',
    owasp: 'A04',
    run: () => fetch(`/api/scenarios/user?access_token=${encodeURIComponent('sk_demo_123')}`),
  },
];

/** Requests the active probes work on: open the trace, then Try security checks > Set up probe. */
const PROBE_TARGETS: { label: string; probe: string; run: () => Promise<Response> }[] = [
  { label: 'Customer record', probe: 'try another ID (IDOR)', run: () => fetch('/api/scenarios/customers/1001') },
  {
    label: 'Update profile',
    probe: 'try an extra field such as role (mass assignment)',
    run: () =>
      fetch('/api/scenarios/customers/1001/update', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: 'Ada Lovelace' }),
      }),
  },
  {
    label: 'Sign-in redirect',
    probe: 'try another site in next (open redirect)',
    run: () => fetch('/api/scenarios/redirect?next=%2Fhome', { redirect: 'manual' }),
  },
  {
    label: 'Tenant data',
    probe: 'try without X-Tenant-Id (auth boundary)',
    run: () => fetch('/api/scenarios/tenant-data', { headers: { 'X-Tenant-Id': 'acme' } }),
  },
];

interface AskScenario {
  id: string;
  label: string;
  question: string;
  mode?: string;
}

const GOOD_MATCH: AskScenario = { id: 'good', label: 'Good match', question: 'When are refunds issued?' };

const ASK_SCENARIOS: AskScenario[] = [
  GOOD_MATCH,
  { id: 'off-topic', label: 'Low relevance (nothing in the documents answers this)', question: 'Can I pay with crypto?' },
  { id: 'cut-off', label: 'Answer cut off (a tight output token limit)', question: 'When are refunds issued?', mode: 'cut-off' },
  { id: 'reranked', label: 'Chunks miss the prompt (a reranker keeps only the best one)', question: 'When are refunds issued?', mode: 'reranked' },
];

/** A question for the shop's RAG assistant: the trace shows the search, the prompt and the answer. */
function Assistant() {
  const [scenario, setScenario] = useState(GOOD_MATCH);
  const [question, setQuestion] = useState(GOOD_MATCH.question);
  const [answer, setAnswer] = useState<string>();

  const ask = () =>
    trace('Ask assistant', async () => {
      const response = await fetch('/api/ask', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question, mode: scenario.mode }),
      });
      setAnswer(response.ok ? ((await response.json()) as { answer: string }).answer : `HTTP ${response.status}`);
    });

  return (
    <fieldset style={{ marginTop: 24 }}>
      <legend>Assistant (RAG)</legend>
      <p style={{ color: '#666', fontSize: 14, marginTop: 0 }}>
        Spring AI searches the shop&apos;s documents and asks a fake local model. Open the trace and click the{' '}
        <strong>Retrieval</strong> or <strong>AI model</strong> span.
      </p>
      {ASK_SCENARIOS.map((option) => (
        <label key={option.id} style={{ display: 'block' }}>
          <input
            type="radio"
            name="ask-scenario"
            checked={option.id === scenario.id}
            onChange={() => {
              setScenario(option);
              setQuestion(option.question);
            }}
          />{' '}
          {option.label}
        </label>
      ))}
      <input
        aria-label="Question"
        value={question}
        onChange={(e) => setQuestion(e.target.value)}
        style={{ width: '100%', padding: 6, marginTop: 8, boxSizing: 'border-box' }}
      />
      <button onClick={() => void ask()} style={{ marginTop: 8 }}>
        Ask
      </button>
      {answer && <p style={{ fontSize: 14 }}>{answer}</p>}
    </fieldset>
  );
}

function SecurityScenarios() {
  const [ran, setRan] = useState<string>();

  const fire = (scenario: { label: string; run: () => Promise<Response> }) =>
    trace(`Security: ${scenario.label}`, async () => {
      try {
        await scenario.run();
      } catch {
        // The point is the captured trace, not the response.
      }
      setRan(scenario.label);
    });

  return (
    <fieldset style={{ marginTop: 24, borderColor: '#d33' }}>
      <legend style={{ color: '#d33' }}>Security test scenarios</legend>
      <p style={{ color: '#666', fontSize: 14, marginTop: 0 }}>
        Each button sends one deliberately-insecure request (dev only), so a Causeline security check fires on its trace.
        Open the trace and the <strong>Try security checks</strong> guide to see them.
      </p>
      <div style={{ display: 'grid', gap: 6 }}>
        {SCENARIOS.map((scenario) => (
          <button
            key={scenario.label}
            onClick={() => void fire(scenario)}
            style={{ textAlign: 'left', padding: '6px 10px' }}
          >
            <span style={{ fontFamily: 'monospace', color: '#d33', marginRight: 8 }}>{scenario.owasp}</span>
            {scenario.label}
          </button>
        ))}
      </div>
      <p style={{ color: '#666', fontSize: 14, marginBottom: 6 }}>
        <strong>Probe targets.</strong> Send one, open its trace, then use <strong>Try security checks → Set up probe</strong>.
      </p>
      <div style={{ display: 'grid', gap: 6 }}>
        {PROBE_TARGETS.map((target) => (
          <button
            key={target.label}
            onClick={() => void fire(target)}
            style={{ textAlign: 'left', padding: '6px 10px' }}
          >
            <span style={{ fontFamily: 'monospace', color: '#d33', marginRight: 8 }}>probe</span>
            {target.label} <span style={{ color: '#666' }}>— {target.probe}</span>
          </button>
        ))}
      </div>
      {ran && (
        <p style={{ fontSize: 14 }}>
          Ran “{ran}”. Open <code>/causeline/</code> and look at the newest trace.
        </p>
      )}
    </fieldset>
  );
}
