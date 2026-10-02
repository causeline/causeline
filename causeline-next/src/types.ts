// SPDX-License-Identifier: Apache-2.0

/** Options for {@link registerCauseline} and {@link createCauselineSpanProcessor}. */
export interface CauselineNextOptions {
  /**
   * Base URL of the Causeline embedded in your Spring Boot app.
   * Default: `process.env.CAUSELINE_ENDPOINT`, else `http://localhost:8080/causeline`.
   */
  endpoint?: string;
  /**
   * That app's Causeline access token (`causeline.access-token`). Default: `process.env.CAUSELINE_TOKEN`.
   * Without it nothing is sent.
   */
  token?: string;
  /** Name shown for these spans in the timeline. Default: `process.env.CAUSELINE_SERVICE_NAME`, else `next`. */
  serviceName?: string;
  /**
   * Server-side `fetch` calls that get a `traceparent` header, so the service they call joins the
   * trace. Localhost and the endpoint's host always do; add your API's other origins here
   * (URL prefixes, or regular expressions tested against the full URL). Third parties never do
   * unless listed.
   */
  propagateTo?: (string | RegExp)[];
  /**
   * Whether Causeline runs at all. Default: on in development, off when `NODE_ENV` is
   * `production`, like the rest of Causeline. Set it to `true` only for a local production build.
   */
  enabled?: boolean;
  /**
   * Hide values of query parameters whose names match, in addition to the defaults
   * (token, secret, password, session, auth), before spans leave the Next.js server.
   */
  redactKeys?: string[];
}

/** A span in Causeline's schema, as the Spring side accepts it on `/causeline/api/peer-spans`. */
export interface CauselineSpan {
  traceId: string;
  spanId: string;
  parentSpanId: string | null;
  kind: CauselineSpanKind;
  name: string;
  source: string;
  startTimeUnixNano: string;
  durationNanos: number;
  status: 'OK' | 'ERROR' | 'UNSET';
  attributes: Record<string, string>;
}

export type CauselineSpanKind = 'REQUEST' | 'CONTROLLER' | 'SERVICE' | 'HTTP_CLIENT' | 'EXCEPTION' | 'RENDER';
