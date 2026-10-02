// SPDX-License-Identifier: Apache-2.0
import type { Status } from './api';

function megabytes(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(bytes < 10 * 1024 * 1024 ? 1 : 0)} MB`;
}

/** Lost data explained: which spans were dropped and why. Nothing is shown when nothing was lost. */
export function DropWarning({ status }: { status: Status | undefined }) {
  if (!status) {
    return null;
  }
  const problems: string[] = [];
  if (status.serverSpansDropped > 0) {
    problems.push(`${status.serverSpansDropped} server spans dropped (export queue full)`);
  }
  if (status.browserSpansDropped > 0) {
    problems.push(`${status.browserSpansDropped} browser spans dropped (SDK buffer full)`);
  }
  if (status.browserSpansRejected > 0) {
    problems.push(`${status.browserSpansRejected} browser spans rejected as malformed`);
  }
  if (status.otlp.enabled && status.otlp.dropped > 0) {
    problems.push(`${status.otlp.dropped} spans not exported to ${status.otlp.endpointHost}`);
  }
  if (problems.length === 0) {
    return null;
  }
  return (
    <div role="status" className="border-b border-amber-500/40 bg-amber-500/10 px-6 py-2 text-xs">
      Some traces may be incomplete: {problems.join('; ')}.
    </div>
  );
}

/** Store usage and export activity, for the footer of the trace list. */
export function StatusLine({ status }: { status: Status | undefined }) {
  if (!status) {
    return null;
  }
  return (
    <p className="border-t border-neutral-200 px-4 py-2 text-[11px] text-neutral-500 dark:border-neutral-800 dark:text-neutral-400">
      {status.traces} traces · {megabytes(status.estimatedBytes)} of {megabytes(status.maxBytes)}
      {status.evictedTraces > 0 && ` · ${status.evictedTraces} older traces evicted`}
      {status.otlp.enabled &&
        ` · exporting to ${status.otlp.endpointHost}: ${status.otlp.exported} sent` +
          (status.otlp.failedRequests > 0 ? `, ${status.otlp.failedRequests} failed requests` : '')}
    </p>
  );
}
