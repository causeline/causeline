// SPDX-License-Identifier: Apache-2.0

/** Wall-clock milliseconds with sub-millisecond precision. */
export const nowMs = (): number => performance.timeOrigin + performance.now();

/** Epoch nanoseconds as an exact decimal string (microsecond precision). */
export const toUnixNanoString = (ms: number): string => (BigInt(Math.floor(ms * 1000)) * 1000n).toString();

export const toDurationNanos = (startMs: number, endMs: number): number =>
  Math.max(0, Math.round((endMs - startMs) * 1_000_000));
