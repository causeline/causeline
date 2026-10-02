// SPDX-License-Identifier: Apache-2.0

function randomHex(bytes: number): string {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  let hex = '';
  for (const b of buffer) {
    hex += b.toString(16).padStart(2, '0');
  }
  // W3C Trace Context forbids all-zero IDs.
  return /^0+$/.test(hex) ? randomHex(bytes) : hex;
}

/** 32 lowercase hex characters (W3C trace-id). */
export const newTraceId = (): string => randomHex(16);

/** 16 lowercase hex characters (W3C parent-id / span id). */
export const newSpanId = (): string => randomHex(8);

export const traceparent = (traceId: string, spanId: string): string => `00-${traceId}-${spanId}-01`;
