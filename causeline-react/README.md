# @causeline/react

React SDK for [Causeline](https://github.com/causeline/causeline): see what happens between a React click and a Spring Boot response, as one timeline.

It links your click handlers, `fetch` and XHR calls, and React state updates to the Spring Boot work they cause, using W3C `traceparent`. The trace opens in Causeline's UI inside your Spring Boot app at `/causeline`.

> **Alpha.** This package needs the `dev.causeline:causeline-spring-boot` starter (Maven Central, `0.1.0-alpha.1`) in your backend. See the [quick start](https://github.com/causeline/causeline/blob/main/docs/QUICKSTART.md).

## Install

```bash
npm install @causeline/react
```

Requires React 18 or later (tested with React 19). No other dependencies; about 8 KB gzipped.

## Use

```tsx
import { CauselineProvider, trace, useTracedState } from '@causeline/react';

createRoot(root).render(
  <CauselineProvider endpoint="/causeline/api/spans">
    <App />
  </CauselineProvider>,
);

function Checkout() {
  const [status, setStatus] = useTracedState('checkoutStatus', 'idle');

  const onCheckout = () =>
    trace('Checkout', async () => {
      const response = await fetch('/api/orders', { method: 'POST', body: JSON.stringify(cart) });
      setStatus(response.ok ? 'paid' : 'failed');
    });

  return <button onClick={onCheckout}>Checkout</button>;
}
```

The provider does nothing in production builds (`import.meta.env.PROD`) or without an `endpoint`, so it is safe to leave in.

## API

| Export | What it does |
| --- | --- |
| `CauselineProvider` | Starts capture. Props: `endpoint`, `propagateTo` (origins that get `traceparent`; default: same origin), `ignore` (paths or RegExps to leave untraced), `captureClicks`, `capture`. |
| `trace(name, fn)` | Names a user action; requests and state updates inside it join its trace. Use `trace(name, (ctx) => ctx.fetch(...))` when actions can overlap. |
| `useTracedState(key, initial)` | `useState` that records each update in the trace. |
| `useTracedCallback(name, fn)` | A callback wrapped in `trace()`. |
| `causelineReduxMiddleware` | Records Redux actions dispatched during a traced action. |
| `CauselineProfiler` | Records the React renders an action caused. |

## What it records

By default: full URLs, request and response bodies (text, up to 16 KB each), state values and Redux payloads. These are shown only in your local Causeline UI, and secrets are redacted when a trace is exported. To record less:

```tsx
<CauselineProvider endpoint="/causeline/api/spans" capture={{ query: false, bodies: false, stateValues: false }}>
```

Development and QA only: Causeline refuses to run under a production profile.

## License

[Apache 2.0](https://github.com/causeline/causeline/blob/main/LICENSE)
