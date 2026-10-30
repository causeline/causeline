# Causeline

**See what happens between a React click and a Spring Boot response.**

Causeline is an open-source, local-first developer tool. It follows one user action from a React click, through your Spring Boot controllers, services, repositories and outbound calls, and back to the React state update, and shows it all as one timeline. You can then replay a captured request against Local, Dev or QA and compare the two runs. Your coding agent (Claude Code, Cursor, Copilot) can read the same traces and replay requests over MCP.

> **Status: alpha (`0.1.0-alpha.4`).** Milestones 0.1 (timeline), 0.2 (diagnosis) and 0.3 (replay) work locally, on Spring MVC and WebFlux, with React or Next.js in front: a React click and the Spring Boot work it causes appear as one trace, with bottleneck and exception insights, and any captured request can be replayed and compared with the original. To try it in your own app, start with the [quick start](docs/QUICKSTART.md).

## Why

Debugging "Checkout is slow and sometimes fails" means switching between the browser Network tab, React DevTools, Spring logs, a debugger and Postman. Causeline links them with W3C `traceparent` and OpenTelemetry, so one trace answers: *what happened between the click and the result on screen?*

- **Local-first.** Runs inside your Spring Boot app at `/causeline`. No account, no cloud, no extra process.
- **See exactly what was sent.** Headers, cookies, bodies, SQL and exceptions are captured and shown by default, so nothing is hidden while you debug. Block anything you don't want captured. Exports (OTLP, trace files) redact secrets.
- **Development and QA only.** Off by default, and refuses to run under a production profile.
- **Works with your coding agent.** Claude Code, Cursor or Copilot can read the trace over MCP, fix the code and replay the request to prove it, with secrets redacted and only the replays you allow. See [Use it from your coding agent](#use-it-from-your-coding-agent-mcp).

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

To try MCP with the demo, start the backend with a fixed token and let the agent replay checkouts:

```bash
CAUSELINE_MCP_REPLAY_ALLOW="POST /api/orders" ./mvnw -pl examples/spring-demo spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--causeline.access-token=dev
claude mcp add --transport http causeline http://localhost:8080/causeline/mcp --header "X-Causeline-Token: dev"
```

In Windows Command Prompt, set the variable first with `set "CAUSELINE_MCP_REPLAY_ALLOW=POST /api/orders"` and run `mvnw.cmd`. Then tick "Times out", click **Checkout**, and ask your agent why it failed.

## Using it in your app

**Start with the [quick start](docs/QUICKSTART.md)** (about five minutes).

```xml
<dependency>
  <groupId>dev.causeline</groupId>
  <artifactId>causeline-spring-boot</artifactId>
  <version>0.1.0-alpha.4</version>
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
      enabled: true                    # false: record no request or response headers
      block: []                        # e.g. [Cookie, Set-Cookie]: never recorded or replayed
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
  load:                                # load replay: the API refuses runs beyond these
    max-requests: 1000                 # per run, baseline included
    max-concurrency: 20
    max-duration: 60s
    keep-slowest: 5                    # full traces kept per request; the rest are folded into the figures
    keep-failed: 20
  mcp:
    enabled: true                      # /causeline/mcp for your coding agent (see "Use it from your coding agent")
    hide: []                           # path patterns no MCP tool shows or replays, e.g. /api/admin/**
    replay:
      allow: []                        # "METHOD /pattern" the agent may replay; empty = GET and HEAD only
      deny: []                         # always wins over allow
      max-per-minute: 20
  explain:
    enabled: true                      # "Explain query" may ask your database to EXPLAIN a captured SELECT
    timeout: 5s
  contract:
    enabled: true                      # "Check contract" may read your OpenAPI document
    spec-url:                          # default: this app's springdoc endpoint, /v3/api-docs
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
- **Transaction problems:** three more insights, each naming the spans involved and the fix.
  - *Writes not in one transaction:* one request wrote to the database in separate transactions, so a failure halfway leaves the earlier writes behind.
  - *Lazy loading after the transaction:* SELECTs ran in the controller or while the response was written, after the transaction had ended (open-session-in-view).
  - *Transaction open during an HTTP call:* a transaction, and its database connection, was held for 100 ms or more while waiting for another service.
- **Security hints:** rules that read the traffic Causeline already captured, each tagged with its OWASP Top 10:2025 category. They send nothing and change nothing, and they are hints, not a security scan.
  - *Request value written into SQL* (A05): a value from the query string, a path variable or the body appears as a literal in that request's prepared SQL, so it was concatenated instead of bound. Needs `capture.sql: full`, the default.
  - *Missing security headers* (A02): no `X-Content-Type-Options: nosniff`; on HTML, no `Content-Security-Policy` or frame protection.
  - *Server version disclosed* (A02) and *CORS allows any origin with credentials* (A02).
  - *Session cookie without HttpOnly / SameSite* (A07) and *JWT without signature or expiry* (A07).
  - *Secret in URL* (A04): a password, token or API key in a query string, in the browser or on the server.
  - *Error details sent to the client* (A10): a stack trace or SQL error in a response body.
  - *Secret written to a log* (A09): a credential from the request appears in a log line of the same trace.
  - *Secret in the response* (A04): a response field named like a secret (password, passwordHash, ssn) with a real value.
  - *Request value steers an outbound call* (A01, SSRF): a request value appears in the URL of an outbound call the server made.
  - These last three use what only Causeline sees — the logs, outbound calls and response of one whole trace — so they catch things an outside scanner can't.
  - Findings name the request, field, header or cookie, never the captured value. Response headers are captured for these rules under the same `capture.headers` settings as request headers; exports keep cookie flags but redact cookie values.
- **Frontend problems:** *request waterfall* (GET requests of one action that ran one after another and could start together), *re-render storm* (a `CauselineProfiler` tree that rendered five or more times in one action) and *state update after unmount* (a `useTracedState` setter called after its component was gone).

**Debugging tools:**

- **Open in editor:** on a controller, service or repository span, or on an exception's location, open the code in VS Code, IntelliJ IDEA or Cursor, at the method or the line that threw. Causeline finds the source file under the application's working directory, and only ever shows its path.
- **Copy as cURL, HTTPie or fetch, or as a test:** on any request span, copy a cURL command, an HTTPie command, a JavaScript `fetch` call, a MockMvc test (Spring MVC) or a WebTestClient test (WebFlux) built from what was captured. Credentials, and the headers a browser adds by itself (`user-agent`, `sec-*`, `referer` and the like), are left out of the tests, so a test can be pasted as it is. The test also asserts what the trace did, using the guard below.
- **Search inside traces:** the search box finds traces by an order ID in a body, a table in SQL, a header value or a log line, not just by name.
- **Compare with…:** put any two runs side by side, span by span, such as yesterday's fast checkout and today's slow one. Under the spans, the response bodies are compared field by field: what changed, what was added, what is gone. Replay comparisons show the same.
- **Explain query:** on a SELECT, ask your database for its plan. Causeline points out full table scans (and, on MySQL, sorting or grouping without an index) and shows the plan.
  - The query is planned, never run: no `EXPLAIN ANALYZE`, on a read-only connection, in a transaction that is rolled back.
  - Only SELECTs this application recorded itself, with the captured values bound as parameters (needs `causeline.capture.sql=full`, the default).
  - H2, PostgreSQL, MySQL and MariaDB, through the application's JDBC `DataSource`. R2DBC is not supported yet.
  - Your local tables are small: a database may scan them even where it would use an index on real data, so treat a finding as a hint.
- **Check contract:** on a server request, compare the response with your OpenAPI document and with the run before.
  - Against the document (springdoc's `/v3/api-docs`, or `causeline.contract.spec-url`): fields the document doesn't list, wrong types, missing required fields, values outside an enum, and status codes or operations it doesn't describe.
  - Against the latest earlier run of the same request: fields that are gone and types that changed, which is what breaks a client.
  - It is lenient on purpose: `null` is accepted anywhere, and formats and number ranges are not checked.
  - Requests for the OpenAPI document are not traced.
- **Slower than usual:** a run is flagged when it's slower than the median of other runs of the same action. The trace list can also filter by errors, slow runs and replays.

### Guard it in a test

What you saw in a trace can be kept as a JUnit 5 test, so it stays true after you close the browser. Add `causeline-test` in test scope:

```xml
<dependency>
  <groupId>dev.causeline</groupId>
  <artifactId>causeline-test</artifactId>
  <version>0.1.0-alpha.4</version>
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

**Try security checks.** The **Try security checks** button next to Replay opens a guide that explains each check, why it matters and how to fix it, and shows a **Security overview** across all your traces (grouped by rule, with per-rule dismiss). **Fill in** loads a case into the replay form: a token in the URL, an unsigned JWT, the request without credentials, or a malformed body. You review it and replay; the result lists what the checks found, and the "without credentials" and "unsigned JWT" cases give a plain verdict from the HTTP status (for example "answered 201 without credentials — this endpoint does not check who is calling"). Traces with findings also have an **About security checks** button.

Guard a fix in a test with `TraceAssert.hasNoSecurityFindings()` (see below), so a finding you fixed fails the build if it comes back.

The demo has a **Security test scenarios** panel with a **Probe targets** block for the active probes (a customer record, a profile update, a sign-in redirect and tenant data). Its scenario buttons: each button sends one deliberately-insecure request (dev profile only) so a check fires on its trace — a value built into SQL (A05), a secret returned in the response (A04), a credential written to a log (A09), an insecure session cookie (A07), a server version and permissive CORS (A02), a request value steering an outbound call (SSRF, A01), a stack trace in the response (A10), and a secret in the URL (A04). Click a few, then open the guide to see them grouped in the overview.

**Active probes.** In the replay form, **Active probe** changes one thing in the request and tells you what the replay revealed. You type the input, and press **Replay now** yourself; each probe is one request, never a scan.
- *Another ID (IDOR):* you type the other ID (and where it goes, if it isn't the first path variable). A 2xx with a different response means nothing checks who owns each object.
- *Extra field (mass assignment):* you type a field name, such as `role` or `isAdmin`. If its value reaches an INSERT or UPDATE, the client controls a field it should not. Needs a JSON body and `causeline.capture.sql=full`.
- *Redirect elsewhere (open redirect):* you type the redirect parameter, such as `next`. The target is a reserved `.invalid` address; a `Location` pointing at it is the finding.
- *Without a header (auth boundary):* you type a header, such as `X-Tenant-Id`. The same answer without it means the server ignores it.

A refusal (401, 403, 4xx) is reported as good news. The evidence is the replay's trace, so verdicts are for replays to this application (`local`); other targets show the HTTP status. The guide's **Set up probe** buttons open a probe in the form.

**Edit the request first.** Tick "Edit the request before sending" to change the path, query string, headers or body. The form starts from what was captured; only what you change differs from the original. Use it to try a fix or a different value, or to see how your app handles a request without `Authorization` or with a token in the URL. The replay gets its own trace, with the usual insights and security hints. The edited request still goes only to the chosen target: the host can't be changed, and `Host`, `traceparent` and Causeline's replay header are always set by Causeline.

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

### Load replay

**Load test…** (or the **Load** tab of the replay panel) sends the captured request many times at once, to find out what slows down when several people do the same thing. Every hit is an ordinary replay with its own trace, so the result says *which span* got slower, not only that the endpoint did.

1. Choose **This request**, or **The whole flow**: every replayable request of the trace, in order, with the user's original pauses between them (at most 5 s each), as one virtual user.
2. Choose the shape: a number of hits with a fixed number **in flight**, optionally ramping (1, 2, 4 … up to it), or a fixed rate per second for a number of seconds.
3. Press **Start load**. It first sends 5 hits one at a time (the baseline), then the load. Requests that change data need a confirmation that names how many times they will run.

The result shows, per request:
- latency p50, p95, p99 and max for the baseline, the load and each ramp step, plus a p95-per-second strip;
- hits by HTTP status, and exceptions by class and the line that threw them, marked when they happened only under concurrency;
- **What grew**: each span's self time in the baseline and under load. A span is highlighted when its p95 grew by at least 20 ms and 25%, and the span that grew most comes first. Queries per hit are shown for both phases, so a count that rises with concurrency stands out;
- links to the full traces of the 5 slowest hits and any failed ones. Every other hit's trace is folded into the figures and removed, so a run doesn't push your other traces out.

The load comes from your application's own JVM, on your machine, so use the numbers to compare runs and spans, not to plan capacity. For that, **Copy as k6** gives a [k6](https://k6.io) script for the same request or flow and shape, without the captured credentials (it reads `CAUSELINE_AUTH` and `BASE_URL` from the environment), to run from another machine.

Load replay goes to `local`, or to a target with `allow-load: true`; a shared environment is never loaded by default. On another target you get each hit's status and time, but no span analysis. It can't pause at steps, and active probes are not available in it. Only one run goes at a time, and **Cancel** stops it starting new hits.

```yaml
causeline:
  replay:
    targets:
      qa:
        base-url: https://qa.example.internal
        allow-load: true                 # let load replay send many requests here
```

In the demo, tick **Contended** under the payment provider (and untick **Slow**), place one order, then load-test it with 20 in flight: `PaymentClient.charge` comes out on top of *What grew*, because it lets one charge through at a time.

### Use it from your coding agent (MCP)

Causeline serves its traces to your coding agent over the [Model Context Protocol](https://modelcontextprotocol.io) at `/causeline/mcp`. Claude Code, Claude Desktop, Cursor, VS Code with Copilot, Windsurf or any other MCP client can then read what really happened in a failing or slow request, fix the code, replay the original request and compare the two runs. You don't have to retell it what the timeline shows. Causeline itself runs no AI: it answers your agent.

#### 1. Enable it

MCP is on whenever Causeline is enabled. Nothing else is needed for the agent to read traces. Set a fixed access token so your agent's configuration keeps working across restarts:

```yaml
# application-dev.yml
causeline:
  enabled: true
  access-token: ${CAUSELINE_TOKEN:dev-only-token}   # without this, the token changes on every restart
  mcp:
    replay:
      allow: ["POST /api/orders"]   # optional: let the agent replay this request (GET and HEAD are allowed by default)
```

Start the app. Its log prints the command to connect:

```
Causeline UI: http://localhost:8080/causeline/  (use your configured causeline.access-token)
Causeline MCP: claude mcp add --transport http causeline http://localhost:8080/causeline/mcp --header "X-Causeline-Token: <causeline.access-token>"
```

In the Causeline UI, **Connect agent** in the header shows the same command with your token filled in. It masks the token on screen and copies it in full.

#### 2. Connect your agent

Every client needs the same two things: the URL `http://localhost:8080/causeline/mcp` and the header `X-Causeline-Token: <token>`. `Authorization: Bearer <token>` works too. Use your app's port if it isn't 8080, and keep the app running while the agent uses it.

**Claude Code**

```bash
claude mcp add --transport http causeline http://localhost:8080/causeline/mcp --header "X-Causeline-Token: <token>"
```

Add `--scope project` to save it in the project's `.mcp.json` for your team. Leave the token out of that file and use an environment variable instead: `--header "X-Causeline-Token: ${CAUSELINE_TOKEN}"`.

**Claude Desktop**

Claude Desktop starts local MCP servers as programs, so it reaches Causeline through the `mcp-remote` bridge, which `npx` downloads the first time. Open **Settings → Developer → Edit Config** and add `mcpServers` at the top level of `claude_desktop_config.json`, next to whatever is already there:

```json
{
  "mcpServers": {
    "causeline": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8080/causeline/mcp", "--header", "X-Causeline-Token:<token>"]
    }
  }
}
```

Write the header without a space after the colon; arguments with spaces get mangled on Windows. Quit Claude Desktop completely (from the system tray or menu bar) and start it again. **Settings → Connectors → Add custom connector** won't work here: those connections come from Anthropic's servers, which can't reach your `localhost`.

**Cursor**

In `.cursor/mcp.json` (this project) or `~/.cursor/mcp.json` (every project):

```json
{
  "mcpServers": {
    "causeline": {
      "url": "http://localhost:8080/causeline/mcp",
      "headers": { "X-Causeline-Token": "<token>" }
    }
  }
}
```

**VS Code with GitHub Copilot**

In `.vscode/mcp.json`:

```json
{
  "servers": {
    "causeline": {
      "type": "http",
      "url": "http://localhost:8080/causeline/mcp",
      "headers": { "X-Causeline-Token": "<token>" }
    }
  }
}
```

**Any other client:** use the Streamable HTTP transport with the URL and header above. To try the tools by hand, run `npx @modelcontextprotocol/inspector`. Choose **Streamable HTTP**, enter the URL, add the header under **Authentication** and switch it on, then **Connect** and **List Tools**.

#### 3. Check it works

- In Claude Code, `/mcp` lists **causeline** as connected, with 7 tools. Other clients show it in their MCP or tools settings.
- Ask: *"Use causeline to list recent traces."*
- In the Causeline UI, the header switches from **Connect agent** to **Agent connected** after the first tool call.

#### 4. Ask it things

- *"Use causeline: why did the last checkout fail?"* The agent reads the trace and names the exception and the file and line that threw it.
- *"Which part of the last checkout is slowest, and why?"*
- *"Are there N+1 queries or repeated SQL in recent traces?"*
- *"What security issues has causeline found across all traces?"*
- *"Fix the cause, then replay the failed checkout and compare it with the original."* After you restart the app with the fix, the agent replays the request and reports, for example, 500 → 201 with the exception gone.

Replays the agent sent show in the trace list with an **Agent** tag, and **Connect agent** lists them under "Replayed by your agent".

#### Tools

Six tools only read; `replay_request` sends a request, so MCP clients ask you before each call.

| Tool | What the agent gets |
| --- | --- |
| `list_traces` | Recent traces, newest first, optionally only failed ones or by name |
| `get_trace` | The span tree with self time, captured values and insights, and which requests can be replayed |
| `get_insights` | Bottleneck, N+1 queries, handled exceptions, transaction and frontend hints |
| `security_findings` | Security hints for one trace, or grouped across all of them |
| `compare_traces` | Two traces compared span by span |
| `source_location` | The file and line of a class |
| `replay_request` | Sends a captured request again, optionally with another path, query or body, and on `local` compares it with the original |

#### What the agent sees

Everything an agent receives is redacted like an exported trace: credential headers, sensitive values and SQL literals are hidden, because an agent's context usually goes to a model provider. Long values are cut. The local UI still shows everything. To keep some requests away from the agent entirely:

```yaml
causeline:
  mcp:
    hide: ["/api/admin/**", "/api/payroll/**"]   # no MCP tool shows or replays these
```

`causeline.export.redact-secrets=false` turns redaction off for MCP too, as it does for exports.

#### What the agent may replay

You decide, in configuration. The agent can't change these settings.

```yaml
causeline:
  mcp:
    replay:
      allow: ["GET /api/**", "POST /api/checkout"]   # METHOD /path-pattern (Spring path patterns); * for any method
      deny: ["DELETE /**", "* /api/admin/**"]         # always wins over allow
      max-per-minute: 20
  replay:
    targets:
      qa:
        base-url: https://qa.example.internal
        allow-mcp: true                               # agents may replay here too; off by default
```

- Without an `allow` list the agent may replay only `GET` and `HEAD`.
- The check uses the request as it will be sent, after the agent's edits, so it can't edit its way into a blocked path.
- Replays go to `local`, or to targets with `allow-mcp: true`.
- Requests with side effects (`POST`, `PUT`, `PATCH`, `DELETE`) need the agent's explicit confirmation, and your MCP client asks you to approve the call.
- The agent can't change headers. Original headers, credentials included, are resent as captured (see `send-original-credentials`), and the agent never sees them.
- Paused replays, load runs, active probes and Explain query are not available to agents.
- Every refusal names the setting that would allow the request, so the agent can tell you what to change.

#### Troubleshooting

| Symptom | Fix |
| --- | --- |
| `401` or "access token missing or wrong" | The header is missing, switched off, or has an old token. The generated token changes on every restart: set `causeline.access-token`. |
| `403` "Origin not allowed" | The request came from a web page on another site. MCP clients don't send `Origin`; browser-based tools must run on `localhost`. |
| The client can't connect at all | The app isn't running, runs on another port, or Causeline isn't enabled in the active profile. Open `http://localhost:8080/causeline/` to check. |
| Claude Desktop doesn't list causeline | Check the JSON (a missing comma breaks the whole file), quit Claude Desktop completely and restart. **Settings → Developer** shows the server's log. If it refuses a plain `http://` URL, add `"--allow-http"` to `args`. |
| A replay is refused | The message names the setting: `causeline.mcp.replay.allow`, `deny`, `hide`, a target's `allow-mcp`, or `max-per-minute`. |
| Tools show as read-only | Expected: six of the seven tools only read. `replay_request` is the one that sends requests. If your client won't call it, make sure it isn't in a plan or read-only mode. |

`causeline.mcp.enabled=false` removes the endpoint.

## Contributing

Issues and pull requests are welcome at https://github.com/causeline/causeline. Please report security problems privately through the repository's **Security → Report a vulnerability** page, not in a public issue.

## License

[Apache License 2.0](LICENSE)
