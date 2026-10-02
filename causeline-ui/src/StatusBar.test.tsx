// SPDX-License-Identifier: Apache-2.0
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Status } from './api';
import { DropWarning, StatusLine } from './StatusBar';

const healthy: Status = {
  traces: 12,
  estimatedBytes: 3 * 1024 * 1024,
  maxBytes: 64 * 1024 * 1024,
  evictedTraces: 0,
  serverSpansDropped: 0,
  browserSpansDropped: 0,
  browserSpansRejected: 0,
  otlp: { enabled: false, endpointHost: null, exported: 0, dropped: 0, failedRequests: 0 },
};

const text = (html: string) => html.replaceAll('<!-- -->', '');

describe('DropWarning', () => {
  it('stays silent when nothing was lost', () => {
    expect(renderToString(<DropWarning status={healthy} />)).toBe('');
  });

  it('explains every kind of loss', () => {
    const html = text(
      renderToString(
        <DropWarning
          status={{
            ...healthy,
            serverSpansDropped: 4,
            browserSpansDropped: 7,
            otlp: { enabled: true, endpointHost: 'tempo.internal', exported: 10, dropped: 3, failedRequests: 1 },
          }}
        />,
      ),
    );

    expect(html).toContain('4 server spans dropped (export queue full)');
    expect(html).toContain('7 browser spans dropped (SDK buffer full)');
    expect(html).toContain('3 spans not exported to tempo.internal');
  });
});

describe('StatusLine', () => {
  it('shows memory use and export activity', () => {
    const html = text(
      renderToString(
        <StatusLine
          status={{
            ...healthy,
            otlp: { enabled: true, endpointHost: 'tempo.internal', exported: 120, dropped: 0, failedRequests: 0 },
          }}
        />,
      ),
    );

    expect(html).toContain('12 traces · 3.0 MB of 64 MB');
    expect(html).toContain('exporting to tempo.internal: 120 sent');
  });
});
