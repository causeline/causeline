// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { formatDuration, percent } from './format';

describe('formatDuration', () => {
  it('uses one decimal below a millisecond', () => {
    expect(formatDuration(400_000)).toBe('0.4 ms');
  });

  it('rounds milliseconds', () => {
    expect(formatDuration(876_400_000)).toBe('876 ms');
  });

  it('switches to seconds at 1000 ms', () => {
    expect(formatDuration(1_820_000_000)).toBe('1.82 s');
  });
});

describe('percent', () => {
  it('handles a zero total', () => {
    expect(percent(5, 0)).toBe(0);
    expect(percent(810, 876)).toBe(92);
  });
});
