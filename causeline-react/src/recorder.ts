// SPDX-License-Identifier: Apache-2.0
import type { BrowserSpan } from './types.js';

export interface RecorderOptions {
  endpoint: string;
  /** The unpatched fetch, so uploads are never captured as spans themselves. */
  send: typeof fetch;
  flushIntervalMs?: number;
  maxBatch?: number;
  maxBuffer?: number;
}

/**
 * Buffers finished spans and uploads them in batches: every second, or as soon as a full batch
 * is waiting. The buffer is bounded; when it is full new spans are dropped and counted.
 */
export class Recorder {
  private readonly buffer: BrowserSpan[] = [];
  private readonly flushIntervalMs: number;
  private readonly maxBatch: number;
  private readonly maxBuffer: number;
  private timer: ReturnType<typeof setInterval> | undefined;
  /** Spans still being finished, e.g. waiting for a response body; flush waits for them. */
  private readonly finishing = new Set<Promise<void>>();
  /** Spans dropped because the buffer was full, since the page loaded. */
  dropped = 0;
  /** How many of those the server has been told about. */
  private reported = 0;

  constructor(private readonly options: RecorderOptions) {
    this.flushIntervalMs = options.flushIntervalMs ?? 1000;
    this.maxBatch = options.maxBatch ?? 50;
    this.maxBuffer = options.maxBuffer ?? 500;
  }

  record(span: BrowserSpan): void {
    if (this.buffer.length >= this.maxBuffer) {
      this.dropped++;
      return;
    }
    this.buffer.push(span);
    if (this.buffer.length >= this.maxBatch) {
      void this.flush();
    }
  }

  /** Records the span once `span` resolves; a span that fails to finish is lost, never thrown. */
  recordLater(span: Promise<BrowserSpan>): void {
    const done = span.then(
      (s) => this.record(s),
      () => {},
    );
    this.finishing.add(done);
    void done.finally(() => this.finishing.delete(done));
  }

  async flush(): Promise<void> {
    if (this.finishing.size > 0) {
      await Promise.allSettled([...this.finishing]);
    }
    while (this.buffer.length > 0) {
      const batch = this.buffer.splice(0, this.maxBatch);
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      // Drops are reported with the next upload, so the UI can explain gaps in browser spans.
      const unreported = this.dropped - this.reported;
      if (unreported > 0) {
        headers['X-Causeline-Dropped'] = String(unreported);
      }
      try {
        const response = await this.options.send(this.options.endpoint, {
          method: 'POST',
          headers,
          body: JSON.stringify(batch),
          keepalive: true,
        });
        if (response.ok) {
          this.reported += unreported;
        }
      } catch {
        // Losing debug spans must never break the application.
      }
    }
  }

  start(): void {
    this.timer ??= setInterval(() => void this.flush(), this.flushIntervalMs);
  }

  stop(): void {
    if (this.timer !== undefined) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
    void this.flush();
  }
}
