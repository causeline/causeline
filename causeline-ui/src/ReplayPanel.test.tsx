// SPDX-License-Identifier: Apache-2.0
import type { ReactElement } from 'react';
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Comparison, ReplayOutcome } from './api';
import { ReplayResult, credentialsNote } from './ReplayPanel';

/** Server-rendered HTML without React's `<!-- -->` text separators. */
const text = (node: ReactElement) => renderToString(node).replaceAll('<!-- -->', '');

const outcome: ReplayOutcome = {
  replayTraceId: 'b'.repeat(32),
  target: 'local',
  httpStatus: 201,
  durationNanos: 104_000_000,
  sentRedactedFields: true,
};

describe('credentialsNote', () => {
  const target = { name: 'qa', location: 'https://qa', auth: 'bearer', comparable: false, sendsOriginalCredentials: true };

  it('says the target profile replaces Authorization while other originals are resent', () => {
    expect(credentialsNote(target)).toContain("qa's bearer credentials replace the original Authorization");
  });

  it('warns that the user session goes along when there is no profile', () => {
    expect(credentialsNote({ ...target, auth: 'none' })).toContain("including the user's Authorization and cookies");
  });

  it('says originals are withheld when configured', () => {
    expect(credentialsNote({ ...target, sendsOriginalCredentials: false })).toContain('are not sent');
  });
});

describe('ReplayResult', () => {
  it('shows both runs side by side with what changed', () => {
    const comparison: Comparison = {
      instrumented: true,
      result: {
        original: { traceId: 'a'.repeat(32), status: 'ERROR', durationNanos: 876_000_000, httpStatus: '500' },
        replay: { traceId: 'b'.repeat(32), status: 'OK', durationNanos: 104_000_000, httpStatus: '201' },
        rows: [
          {
            depth: 0,
            kind: 'REQUEST',
            name: 'POST /api/orders',
            originalNanos: 876_000_000,
            replayNanos: 104_000_000,
            originalStatus: 'ERROR',
            replayStatus: 'OK',
            change: 'STATUS_CHANGED',
          },
          {
            depth: 1,
            kind: 'EXCEPTION',
            name: 'PaymentTimeoutException',
            originalNanos: 0,
            replayNanos: null,
            originalStatus: 'ERROR',
            replayStatus: null,
            change: 'ONLY_IN_ORIGINAL',
          },
        ],
      },
    };

    const html = text(<ReplayResult outcome={outcome} comparison={comparison} />);

    expect(html).toContain('Original (500)');
    expect(html).toContain('Replay (201)');
    expect(html).toContain('876 ms');
    expect(html).toContain('status changed');
    expect(html).toContain('only in original');
    expect(html).toContain('sent as [REDACTED]');
  });

  it('explains why there is no comparison for an uninstrumented target', () => {
    const html = text(
      <ReplayResult outcome={{ ...outcome, target: 'qa' }} comparison={{ instrumented: false, result: null }} />,
    );

    expect(html).toContain('HTTP 201');
    expect(html).toContain('causeline.replay.targets.qa.causeline-token');
  });
});
