# @causeline/next

Next.js server tracing for [Causeline](https://github.com/causeline/causeline). It puts the Next.js server in the same timeline as the React click before it and the Spring Boot work after it: requests, route handlers, server actions, rendering and server-side `fetch` calls.

```
click → fetch /api/checkout → Next.js route handler → fetch → Spring Boot controller → service → SQL
```

> **Alpha.** Needs the `dev.causeline:causeline-spring-boot` starter in your Spring Boot backend; the trace opens in its UI at `/causeline`. Use [`@causeline/react`](https://www.npmjs.com/package/@causeline/react) in the browser. See the [quick start](https://github.com/causeline/causeline/blob/main/docs/QUICKSTART.md).

## Install

```bash
npm install @causeline/next @opentelemetry/api
```

Tested with Next.js 16 on the Node.js runtime.

## Use

```ts
// instrumentation.ts, in the project root (or src/)
export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    const { registerCauseline } = await import('@causeline/next');
    registerCauseline({ serviceName: 'storefront' });
  }
}
```

Then start Next.js with your Spring app's `causeline.access-token`:

```bash
CAUSELINE_TOKEN=<token> next dev
```

To get the browser's spans through Next.js, proxy Causeline to the Spring app:

```ts
// next.config.ts
const config = {
  async rewrites() {
    return [{ source: '/causeline/:path*', destination: 'http://localhost:8080/causeline/:path*' }];
  },
};
export default config;
```

and use `<CauselineProvider endpoint="/causeline/api/spans">` from `@causeline/react` in a client component.

## Options

| Option | Default | What it does |
| --- | --- | --- |
| `endpoint` | `CAUSELINE_ENDPOINT`, else `http://localhost:8080/causeline` | The Causeline in your Spring Boot app. |
| `token` | `CAUSELINE_TOKEN` | That app's `causeline.access-token`. Nothing is sent without it. |
| `serviceName` | `CAUSELINE_SERVICE_NAME`, else `next` | How these spans are labelled in the timeline. |
| `propagateTo` | `[]` | More URLs (prefixes or RegExps) whose server-side `fetch` calls get `traceparent`. Localhost and the endpoint's host always do; third parties never do unless listed. |
| `redactKeys` | `[]` | More query parameter names whose values are hidden, besides `token`, `secret`, `password`, `session` and `auth`. |
| `enabled` | off when `NODE_ENV` is `production` | Turn it on for a local production build only. |

## How it works

- **Recording.** Next.js records its own work as OpenTelemetry spans. `registerCauseline` sets up the OpenTelemetry SDK and sends those spans to the Spring app's Causeline, in the background, through `POST /causeline/api/peer-spans`.
- **Linking to the browser.** Next.js continues the browser's trace from the incoming `traceparent`.
- **Linking to Spring.** Next.js does not send `traceparent` on outgoing calls, so this package adds it to server-side `fetch` calls to your own services.
- **Redaction.** Sensitive query values are redacted before spans leave the Next.js server.
- **No noise.** Calls to third parties made outside any request, such as Next.js checking npm for updates, are not sent.

Already set up OpenTelemetry yourself (for example with `@vercel/otel`)? Don't call `registerCauseline`. Add the processor from `createCauselineSpanProcessor({ serviceName: 'storefront' })` to your setup's span processors; it returns `undefined` when Causeline is off. If your setup doesn't add `traceparent` to outgoing fetch calls, also call `propagateFetch(propagationRule(endpoint))`.

## Not covered

The Edge runtime (Edge route handlers and middleware) is not traced.

## License

Apache-2.0
