# Causeline

**See what happens between a React click and a Spring Boot response.**

Causeline is an open-source, local-first developer tool. It follows one user action from a React click, through your Spring Boot controllers, services, repositories and outbound calls, and back to the React state update, and shows it all as one timeline. You can then replay a captured request against Local, Dev or QA and compare the two runs.

> **Status: alpha (`0.1.0-alpha.0`).** Milestones 0.1 (timeline), 0.2 (diagnosis) and 0.3 (replay) work locally: a React click and the Spring Boot work it causes appear as one trace, with bottleneck and exception insights, and any captured request can be replayed and compared with the original. To try it in your own app, start with the [quick start](docs/QUICKSTART.md).

## Why

Debugging "Checkout is slow and sometimes fails" means switching between the browser Network tab, React DevTools, Spring logs, a debugger and Postman. Causeline links them with W3C `traceparent` and OpenTelemetry, so one trace answers: *what happened between the click and the result on screen?*

- **Local-first.** Runs inside your Spring Boot app at `/causeline`. No account, no cloud, no extra process.
- **See exactly what was sent.** Headers, cookies, bodies, SQL and exceptions are captured and shown by default, so nothing is hidden while you debug. Block anything you don't want captured. Exports (OTLP, trace files) redact secrets.
- **Development and QA only.** Off by default, and refuses to run under a production profile.

## Repository layout

| Path | What |
| --- | --- |
| `causeline-core` | Span model, self-time calculation, trace store (no Spring dependency) |
| `causeline-spring-boot` | Spring Boot auto-configuration, instrumentation, embedded UI and API |
| `causeline-ui` | Trace explorer UI (React, Vite, Tailwind), packaged into the starter |
| `causeline-react` | `@causeline/react` SDK |
| `examples/spring-demo` | Checkout API used for demos and end-to-end tests |
| `examples/react-demo` | Checkout page using the SDK |

## Building from source

Requirements: JDK 21+, Node 22+. Maven comes with the wrapper.

```bash
npm install
npm run build        # SDK, UI, demo
npm test
./mvnw verify        # Java modules and tests (builds the UI into the starter)
```

Run the demo in two terminals:

```bash
npm run build -w @causeline/react && ./mvnw -q install -DskipTests
./mvnw -pl examples/spring-demo spring-boot:run -Dspring-boot.run.profiles=dev
```

The Maven build builds the UI into the starter jar itself. Performance budgets: `./mvnw -Pperf verify` and `npm run perf -w @causeline/react`.

```bash
npm run dev -w react-demo
```

Open http://localhost:5173 and click **Checkout**. Tick "Times out" to see a failing checkout. Then open the link printed in the backend log:

```
Causeline UI: http://localhost:8080/causeline/?token=…
```

The token protects your traces and changes on every restart, unless you set `causeline.access-token`.

## Using it in your app

**Start with the [quick start](docs/QUICKSTART.md)** (about five minutes).

```xml
<dependency>
  <groupId>dev.causeline</groupId>
  <artifactId>causeline-spring-boot</artifactId>
  <version>0.1.0-alpha.0</version>
</dependency>
```

```bash
npm install @causeline/react@alpha
```

In short:

Spring Boot: add `causeline-spring-boot`, set `causeline.enabled=true` in a dev profile, and annotate the service methods you care about with Micrometer's `@Observed`. If your app uses Spring Security, permit `/causeline/**`; Causeline protects those paths itself.

**Supported API.** On the Java side that means the `causeline.*` configuration properties and the `dev.causeline.spring.ReplayAuthProvider` extension point. Everything under `dev.causeline.spring.internal` and `causeline-core` may change without notice. In React, it's everything exported from `@causeline/react`.

React:

```tsx
import { CauselineProvider, trace, useTracedState } from '@causeline/react';

<CauselineProvider endpoint="/causeline/api/spans">
  <App />
</CauselineProvider>;

const [loading, setLoading] = useTracedState('checkoutLoading', false);
const onCheckout = () => trace('Checkout', async () => {
  setLoading(true);
  await fetch('/api/orders', { method: 'POST' });
  setLoading(false);
});
```

If several actions can run at once (a save and a publish overlapping), use the context `trace` passes in, so each request links to exactly its own action:

```ts
const onSave = () => trace('Save draft', (ctx) => ctx.fetch('/api/drafts', { method: 'PUT' }));
```

Optional provider props: `ignore={['/api/health', /analytics/]}` leaves requests untraced, and `captureClicks` labels requests made outside `trace()` with the click that likely caused them. For Redux, add `causelineReduxMiddleware` to your store's middleware.

Wrap parts of the page in `<CauselineProfiler id="Checkout">` to see which renders an action caused.

### Configuration reference

Causeline captures everything by default, secrets included, and shows it in the local UI. The defaults below are what you get without any configuration; change any line to block that data:

```yaml
causeline:
  enabled: true                        # in a dev profile only
  capture:
    headers:
      enabled: true                    # false: record no headers
      block: []                        # e.g. [Cookie, Authorization]: never recorded or replayed
    query:
      enabled: true
      block: []                        # e.g. [coupon]: values show as [REDACTED]
    path-values: true                  # /api/orders/42 next to /api/orders/{id}
    request-body: full                 # replay-only: kept for replay, not shown; none: not kept
    response-body: full                # none: not recorded
    sql: full                          # with bound values filled in; statement: values as ?; operation: "INSERT orders" only
    exception-details: true            # exception messages and stack traces
    redact-keys: []                    # e.g. [password]: hidden everywhere, even locally
  store:
    max-traces: 1000
    max-size: 64MB
  export:
    redact-secrets: true               # OTLP and trace files: credentials and sensitive values redacted
    otlp:
      endpoint: http://localhost:4318/v1/traces   # off unless set; Jaeger, Tempo, an OTel collector, Datadog agent
      headers: { X-API-Key: "${OTLP_API_KEY}" }
  replay:
    send-original-credentials: true    # resend the user's Authorization and cookies
```

In React, `<CauselineProvider capture={{ query: false, stateValues: false, bodies: false }}>` stops recording query strings, state values, and request and response bodies in the browser.

OTLP export sends Causeline's stored spans, never Spring's raw spans, with secrets redacted unless `export.redact-secrets` is false. Use **Export** on a trace to save it as a file for a bug report; **Import trace…** opens such a file in another developer's Causeline. If spans are ever dropped (full buffers, an unreachable exporter), the UI says so.

### Replay

Open a trace and use **Replay…** to send the captured request again, to this app (`local`) or to a target you configure. By default the original headers, credentials and cookies included, are resent; a target's auth profile replaces `Authorization`, and `causeline.replay.send-original-credentials: false` withholds the originals. Requests that change data (POST, PUT, PATCH, DELETE) need an explicit confirmation, because orders, payments and emails will happen again.

```yaml
causeline:
  capture:
    request-body: replay-only        # keep query strings and redacted bodies, in memory only, for replay
  replay:
    targets:
      qa:
        base-url: https://qa.example.internal
        auth: { type: bearer, token: ${CAUSELINE_QA_TOKEN} }
        causeline-token: ${CAUSELINE_QA_API_TOKEN}   # to compare with the replay's trace on QA
```

## Contributing

Issues and pull requests are welcome at https://github.com/causeline/causeline. Please report security problems privately through the repository's **Security → Report a vulnerability** page, not in a public issue.

## License

[Apache License 2.0](LICENSE)
