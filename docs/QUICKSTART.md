# Causeline quick start

Add Causeline to an existing Spring Boot 4 + React app and see your first trace. It takes about five minutes.

You need JDK 21+, Spring Boot 4.1, React 18 or 19, and Node 22.

> **Alpha.** The starter is on Maven Central (`0.1.0-alpha.1`) and the SDKs (`@causeline/react`, `@causeline/next`) on npm. Expect rough edges, and please report them on [GitHub](https://github.com/causeline/causeline/issues).

## 1. Spring Boot: add the dependency

Maven:

```xml
<dependency>
  <groupId>dev.causeline</groupId>
  <artifactId>causeline-spring-boot</artifactId>
  <version>0.1.0-alpha.1</version>
</dependency>
```

Gradle:

```kotlin
implementation("dev.causeline:causeline-spring-boot:0.1.0-alpha.1")
```

## 2. Spring Boot: switch it on in a dev profile

`src/main/resources/application-dev.yml`:

```yaml
causeline:
  enabled: true
```

Causeline is off unless `causeline.enabled=true`. It refuses to start when a `prod*` or `production` profile is active. Keep it in a dev or QA profile only: it captures headers, cookies, bodies and SQL values.

If your app uses **Spring Security**, permit `/causeline/**`. Causeline protects those paths itself with its access token:

```java
http.authorizeHttpRequests(auth -> auth.requestMatchers("/causeline/**").permitAll() /* … your rules … */);
```

## 3. Start the app and open the UI

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The log prints a link with a fresh access token:

```
Causeline UI: http://localhost:8080/causeline/?token=…
```

Open it and call any endpoint (curl, Postman, your front end). Each request shows up as a trace, with its controller, `@Observed` services, repositories, SQL with values, and outbound HTTP calls.

To see your own service methods as separate rows, annotate them with Micrometer's `@Observed`:

```java
@Observed
public Order placeOrder(OrderRequest request) { … }
```

## 4. React: add the provider

```bash
npm install @causeline/react@alpha
```

```tsx
import { CauselineProvider } from '@causeline/react';

createRoot(root).render(
  <CauselineProvider endpoint="/causeline/api/spans">
    <App />
  </CauselineProvider>,
);
```

The provider does nothing in production builds (`import.meta.env.PROD`), so it's safe to leave in.

If the React dev server runs on another port, proxy `/api` and `/causeline` to Spring Boot. This keeps requests same-origin, so no CORS setup is needed. With Vite:

```ts
// vite.config.ts
server: {
  proxy: {
    '/api': 'http://localhost:8080',
    '/causeline': 'http://localhost:8080',
  },
},
```

## 5. Name the click

Wrap a handler in `trace()` so the click, the request, the server work and the state update form one trace:

```tsx
import { trace, useTracedState } from '@causeline/react';

const [status, setStatus] = useTracedState('checkoutStatus', 'idle');

const onCheckout = () =>
  trace('Checkout', async () => {
    const response = await fetch('/api/orders', { method: 'POST', body: JSON.stringify(cart) });
    setStatus(response.ok ? 'paid' : 'failed');
  });
```

Click the button, then open the newest trace in `/causeline`. You'll see the click, the browser request with its bodies, the Spring Boot spans, and the state update, all on one timeline.

## Next steps

- **Something is slow or failing?** The insights panel names the primary bottleneck, repeated queries and exceptions, including exceptions your code catches and logs.
- **Replay** a captured request, with **Replay…** on a trace, and compare the two runs.
- **Block data** you don't want captured: see the configuration reference in the [README](../README.md#configuration-reference).
- **Share a trace:** **Export** writes a file with secrets redacted.

## More than one service?

If your app calls another Spring Boot service that also uses Causeline, point the downstream service at the caller's Causeline:

```yaml
# application-dev.yml of the downstream service
causeline:
  enabled: true
  export:
    upstream:
      url: http://localhost:8080          # the calling service
      token: ${CALLER_CAUSELINE_TOKEN}    # the caller's causeline.access-token
```

Calls between them then show as one trace in the caller's UI.

## WebFlux?

Nothing changes: the same dependency and settings work in a WebFlux app. Methods that return `Mono` or `Flux` get spans that last until the result completes. To see R2DBC queries as SQL spans, add `io.r2dbc:r2dbc-proxy`.

## Next.js in front of Spring?

Step 4 works as written in a client component. To also see the Next.js server (route handlers, server actions, rendering, its calls to Spring) in the same trace:

```bash
npm install @causeline/next@alpha @opentelemetry/api
```

```ts
// instrumentation.ts
export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    const { registerCauseline } = await import('@causeline/next');
    registerCauseline({ serviceName: 'storefront' });
  }
}
```

Start Next.js with `CAUSELINE_TOKEN` set to the Spring app's `causeline.access-token`. Set `CAUSELINE_ENDPOINT` too if the Spring app isn't on `http://localhost:8080`. To get browser spans through Next.js, add a rewrite from `/causeline/:path*` to the Spring app.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| No `Causeline UI:` line in the log | `causeline.enabled` isn't true in the active profile, or a production profile is active. |
| `/causeline` returns 401 | Open the link from the log. The token changes on every restart unless you set `causeline.access-token`. |
| `/causeline` returns 403 "Host not allowed" | You opened it through a hostname other than localhost. Add it to `causeline.allowed-hosts`. |
| `/causeline` returns 403 or a login page with Spring Security | Permit `/causeline/**` (step 2). |
| Browser spans are missing | Check that `endpoint` points at `/causeline/api/spans` on the Spring app and that the page is served from localhost or an allowed origin (`causeline.ingest.allowed-origins`). |
| Browser and server show as separate traces | The request went to another origin. Add it to `propagateTo` on the provider, and allow the `traceparent` header in that server's CORS config. |
