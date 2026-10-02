// SPDX-License-Identifier: Apache-2.0
import { trace, useTracedState } from '@causeline/react';
import { useEffect, useState } from 'react';

type Result = { kind: 'idle' } | { kind: 'ok'; orderId: number } | { kind: 'error'; message: string };

interface Settings {
  slowPayment: boolean;
  failPayment: boolean;
  flakyStock: boolean;
}

export function Checkout() {
  // useTracedState records *that* these changed during a traced action, never their values.
  const [loading, setLoading] = useTracedState('checkoutLoading', false);
  const [result, setResult] = useTracedState<Result>('checkoutResult', { kind: 'idle' });
  const [settings, setSettings] = useState<Settings>({ slowPayment: true, failPayment: false, flakyStock: false });

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
    </main>
  );
}
