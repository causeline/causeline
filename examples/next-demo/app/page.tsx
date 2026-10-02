// SPDX-License-Identifier: Apache-2.0
'use client';

import { CauselineProvider, trace } from '@causeline/react';
import { useState } from 'react';

export default function Page() {
  return (
    <CauselineProvider endpoint="/causeline/api/spans">
      <Checkout />
    </CauselineProvider>
  );
}

function Checkout() {
  const [result, setResult] = useState<string>();
  const [busy, setBusy] = useState(false);

  // Browser → Next.js route handler → Spring Boot: one trace in Causeline.
  const checkout = () =>
    trace('Checkout', async () => {
      setBusy(true);
      try {
        const response = await fetch('/api/checkout', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ item: 'book', quantity: 1 }),
        });
        setResult(`${response.status} ${await response.text()}`);
      } finally {
        setBusy(false);
      }
    });

  return (
    <main>
      <h1>Next.js checkout</h1>
      <p>The button calls a Next.js route handler, which calls the Spring Boot demo.</p>
      <button type="button" onClick={checkout} disabled={busy}>
        {busy ? 'Placing order…' : 'Checkout'}
      </button>
      {result && <pre>{result}</pre>}
    </main>
  );
}
