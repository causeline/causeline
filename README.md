# Causeline

**See what happens between a React click and a Spring Boot response.**

Causeline is an open-source, local-first developer tool. It follows one user action from a React click, through your Spring Boot controllers, services, repositories and outbound calls, and back to the React state update, and shows it all as one timeline. You can then replay a captured request against Local, Dev or QA and compare the two runs.

> **Status: alpha (`0.1.0-alpha.1`).** Milestones 0.1 (timeline), 0.2 (diagnosis) and 0.3 (replay) work locally, on Spring MVC and WebFlux, with React or Next.js in front: a React click and the Spring Boot work it causes appear as one trace, with bottleneck and exception insights, and any captured request can be replayed and compared with the original. To try it in your own app, start with the [quick start](docs/QUICKSTART.md).

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
| `causeline-test` | JUnit 5 guard: assert on the trace a Spring Boot test produced |
| `causeline-ui` | Trace explorer UI (React, Vite, Tailwind), packaged into the starter |
| `causeline-react` | `@causeline/react` SDK |
| `causeline-next` | `@causeline/next`: Next.js server-side tracing |
| `examples/spring-demo` | Checkout API used for demos and end-to-end tests |
| `examples/webflux-demo` | The same checkout on WebFlux, R2DBC and WebClient |
| `examples/react-demo` | Checkout page using the SDK |
| `examples/next-demo` | Next.js page and route handler in front of the Spring demo |

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
  <version>0.1.0-alpha.1</version>
</dependency>
```

```bash
npm install @causeline/react@alpha
```

In short:

Spring Boot: add `causeline-spring-boot`, set `causeline.enabled=true` in a dev profile, and annotate the service methods you care about with Micrometer's `@Observed`. If your app uses Spring Security, permit `/causeline/**`; Causeline protects those paths itself.

**Spring MVC and WebFlux** are both supported, with the same UI, API and settings. On WebFlux:

- The trace follows the request through Reactor operators (Causeline sets `spring.reactor.context-propagation=auto`).
- A controller, `@Observed` service or repository method that returns a `Mono` or `Flux` gets a span that lasts until the result completes. Its "Returned" value is what the result produced: the `Mono`'s value, or the first 25 items of a `Flux`.
- `WebClient` calls become HTTP client spans.
- R2DBC queries become SQL spans with their values filled in. This needs `io.r2dbc:r2dbc-proxy` on the classpath, which is how Spring Boot observes R2DBC.
- Request and response bodies are copied as they stream, never buffered first.

**Supported API.** On the Java side that means the `causeline.*` configuration properties, the `dev.causeline.spring.ReplayAuthProvider` extension point, and `dev.causeline.test` (`@CauselineTest`, `RecordedTraces`, `TraceAssert`) from `causeline-test`. Everything under `dev.causeline.spring.internal` and `causeline-core` may change without notice. In React, it's everything exported from `@causeline/react`.

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

### Next.js

`@causeline/react` works in Next.js client components unchanged. Add `@causeline/next` to also trace the Next.js server. Requests, route handlers, server actions, rendering and server-side `fetch` calls then appear in the same trace as the click and the Spring Boot request they lead to:

```bash
npm install @causeline/next@alpha @opentelemetry/api
```

```ts
// instrumentation.ts (project root)
export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    const { registerCauseline } = await import('@causeline/next');
    registerCauseline({ serviceName: 'storefront' }); // token: CAUSELINE_TOKEN, endpoint: CAUSELINE_ENDPOINT
  }
}
```

How it works:

- **Where spans go.** Next.js spans are sent to the Causeline in your Spring Boot app (default `http://localhost:8080/causeline`). They need that app's `causeline.access-token`, given as `CAUSELINE_TOKEN`.
- **Context to Spring.** Server-side `fetch` calls to localhost, to that app's host, and to anything listed in `propagateTo` carry `traceparent`. Third parties never get it.
- **Redaction.** Sensitive query values are redacted before spans leave the Next.js server.
- **Off by default.** Like the rest of Causeline, it does nothing in production. It is also off without a token and on the Edge runtime.
- **Browser spans.** To get them through Next.js, proxy `/causeline/*` to the Spring app with a rewrite (see `examples/next-demo/next.config.ts`).
- **If you already set up OpenTelemetry** (for example with `@vercel/otel`), pass `createCauselineSpanProcessor()` to it instead of calling `registerCauseline`.

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
    arguments: true                    # arguments and return values of controller, service and repository methods
    logs: info                         # log lines written during a request: off, error, warn, info, debug
    redact-keys: []                    # e.g. [password]: hidden everywhere, even locally
  store:
    max-traces: 1000
    max-size: 64MB
    persist: false                     # true: keep traces across restarts (redacted file in ~/.causeline/<app>)
  export:
    redact-secrets: true               # OTLP, upstream, saved and exported traces: credentials and sensitive values redacted
    otlp:
      endpoint: http://localhost:4318/v1/traces   # off unless set; Jaeger, Tempo, an OTel collector, Datadog agent
      headers: { X-API-Key: "${OTLP_API_KEY}" }
    upstream:
      url: http://localhost:8080       # off unless set: send this service's spans to the calling service's Causeline
      token: ${UPSTREAM_CAUSELINE_TOKEN}
  replay:
    send-original-credentials: true    # resend the user's Authorization and cookies
    pause-timeout: 5m                  # how long a paused replay waits for you before carrying on unchanged
```

In React, `<CauselineProvider capture={{ query: false, stateValues: false, bodies: false, errors: false }}>` stops recording query strings, state values, request and response bodies, and errors in the browser.

OTLP export sends Causeline's stored spans, never Spring's raw spans, with secrets redacted unless `export.redact-secrets` is false. Use **Export** on a trace to save it as a file, or **Bug report** for a single HTML file that opens in any browser; **Import trace…** opens an exported file in another developer's Causeline. If spans are ever dropped (full buffers, an unreachable exporter), the UI says so.

**Beyond a single request thread:**

- Controller, service and repository rows show the method's **arguments and return value** as JSON. Unloaded JPA relations show as `[not loaded]` (rendering never runs a query), servlet requests, streams and files show by type, and values are capped at 4 KB.
- `@Async` methods and Spring's task executor stay in the request's trace (Causeline registers a context-propagating `TaskDecorator` unless your app defines its own).
- Kafka and RabbitMQ sends and receives appear as message spans (Spring Kafka and Spring AMQP observations are switched on).
- **Several services:** in the downstream service, set `causeline.export.upstream.url` and `.token` to the calling service's address and access token. Its spans then appear inside the caller's trace, labelled with the service name.

**On the timeline:**

- **Logs:** what the application logs during a request (INFO and above by default) is listed under the span that wrote it, and in a Logs panel for the whole trace.
- **Browser errors:** uncaught errors and unhandled promise rejections in the page appear as exceptions, under the action they happened in. `console.error` calls made during an action appear as log lines.
- **Transactions and caches:** each transaction appears from begin to commit or rollback, with the queries that ran in it. Each Spring Cache lookup is marked hit or miss, and writes and evictions are shown too.
- **N+1 queries:** repeated SELECTs are named with the query whose rows they load, plus how to fetch them together.

**Debugging tools:**

- **Open in editor:** on a controller, service or repository span, or on an exception's location, open the code in VS Code, IntelliJ IDEA or Cursor, at the method or the line that threw. Causeline finds the source file under the application's working directory, and only ever shows its path.
- **Copy as cURL, or as a test:** on any request span, copy a cURL command, a MockMvc test (Spring MVC) or a WebTestClient test (WebFlux) built from what was captured. Credentials, and the headers a browser adds by itself (`user-agent`, `sec-*`, `referer` and the like), are left out of the tests, so a test can be pasted as it is. The test also asserts what the trace did, using the guard below.
- **Search inside traces:** the search box finds traces by an order ID in a body, a table in SQL, a header value or a log line, not just by name.
- **Compare with…:** put any two runs side by side, span by span, such as yesterday's fast checkout and today's slow one.
- **Slower than usual:** a run is flagged when it's slower than the median of other runs of the same action. The trace list can also filter by errors, slow runs and replays.

### Guard it in a test

What you saw in a trace can be kept as a JUnit 5 test, so it stays true after you close the browser. Add `causeline-test` in test scope (it ships with the release after `0.1.0-alpha.1`):

```xml
<dependency>
  <groupId>dev.causeline</groupId>
  <artifactId>causeline-test</artifactId>
  <version>…</version>
  <scope>test</scope>
</dependency>
```

Put `@CauselineTest` next to `@SpringBootTest` and take a `RecordedTraces` parameter. **Copy as MockMvc test** writes exactly this from a captured request:

```java
@SpringBootTest
@AutoConfigureMockMvc
@CauselineTest
class CreateOrderTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void createsAnOrder(RecordedTraces causeline) throws Exception {
        mockMvc.perform(post("/api/orders").contentType("application/json").content("{\"item\":\"book\",\"quantity\":1}"))
                .andExpect(status().isCreated());

        causeline.trace("POST /api/orders")
                .hasNoFailedSpans()
                .hasQueryCountAtMost(2)
                .hasNoRepeatedQueries()
                .hasSpans("OrderController.createOrder", "OrderService.createOrder", "OrderRepository.save");
    }
}
```

| Check | Fails when |
| --- | --- |
| `hasNoFailedSpans()` | a span failed or an exception was thrown, including one the application caught and logged |
| `hasException("PaymentTimeoutException")` | no exception with that simple class name was thrown or logged |
| `hasQueryCount(n)`, `hasQueryCountAtMost(n)` | the number of SQL statements differs, or exceeds the limit |
| `hasNoRepeatedQueries()` | the same query ran again and again under one parent (N+1), by the rule the UI flags |
| `hasSpan(name)`, `hasSpans(names…)`, `hasSpansInOrder(names…)`, `hasNoSpan(name)` | a controller, service, repository, query or call is missing, out of order, or present when it shouldn't be |

- Every check is about what the application did, never how long it took, so a test gives the same answer on any machine. A failure message prints the whole trace as a tree, with each query's SQL and where each exception was thrown. Values captured from requests are never printed (SQL shows `?` for literals and bind values), because build logs leave your machine.
- `@CauselineTest` sets `causeline.enabled=true` for the test's application context. `causeline.trace()` is the one trace recorded since the test started; `causeline.trace("POST /api/orders")` picks one by a span name when there are several; `causeline.reset()` forgets set-up requests.
- It works with MockMvc, WebTestClient, a real port, or a direct call to an `@Observed` method. Work handed to another thread (`@Async`) may finish after the test has looked, so the generated test doesn't expect it.
- Tests in one class must not run in parallel: a trace can't be told apart from a neighbour's.

### Replay

Open a trace and use **Replay…** to send the captured request again, to this app (`local`) or to a target you configure. By default the original headers, credentials and cookies included, are resent; a target's auth profile replaces `Authorization`, and `causeline.replay.send-original-credentials: false` withholds the originals. Requests that change data (POST, PUT, PATCH, DELETE) need an explicit confirmation, because orders, payments and emails will happen again.

**Pause and change values, like a debugger.** Tick "Pause at steps" and choose controller or `@Observed` service methods.
1. The replay stops at each one, and the UI shows the method's arguments.
2. Edit any of them as JSON and click **Continue**. The method then runs with your values, for example a different item, a quantity of 0, or a declined card.
3. The replay's trace marks the step as edited.

This works for replays to this application (`local`) in Spring MVC applications. A paused replay waits up to `causeline.replay.pause-timeout` (5 minutes by default), then carries on unchanged.

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
