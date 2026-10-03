// SPDX-License-Identifier: Apache-2.0

/** Span kinds a browser may report. Matches the server-side allowlist. */
export type BrowserSpanKind = 'UI_ACTION' | 'REQUEST' | 'STATE_UPDATE' | 'RENDER' | 'EXCEPTION' | 'LOG';

export type SpanStatus = 'OK' | 'ERROR' | 'UNSET';

/** Wire format for `POST /causeline/api/spans`. */
export interface BrowserSpan {
  traceId: string;
  spanId: string;
  parentSpanId: string | null;
  kind: BrowserSpanKind;
  name: string;
  /** Epoch nanoseconds as a decimal string: the value exceeds Number.MAX_SAFE_INTEGER. */
  startTimeUnixNano: string;
  durationNanos: number;
  status: SpanStatus;
  attributes: Record<string, string>;
}
