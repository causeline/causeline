// SPDX-License-Identifier: Apache-2.0

/** Human-readable duration from nanoseconds: "0.4 ms", "42 ms", "1.23 s". */
export function formatDuration(nanos: number): string {
  const ms = nanos / 1_000_000;
  if (ms < 1) {
    return `${ms.toFixed(1)} ms`;
  }
  if (ms < 1000) {
    return `${Math.round(ms)} ms`;
  }
  return `${(ms / 1000).toFixed(2)} s`;
}

/** Local wall-clock time of an epoch-nanosecond timestamp, e.g. "14:03:27". */
export function formatClock(unixNanos: number): string {
  return new Date(unixNanos / 1_000_000).toLocaleTimeString([], { hour12: false });
}

/** Share of the whole, as a whole percentage. */
export function percent(part: number, whole: number): number {
  return whole <= 0 ? 0 : Math.round((part / whole) * 100);
}
