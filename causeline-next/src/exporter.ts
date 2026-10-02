// SPDX-License-Identifier: Apache-2.0
import { SpanKind } from '@opentelemetry/api';
import { ExportResultCode, type ExportResult } from '@opentelemetry/core';
import type { ReadableSpan, SpanExporter } from '@opentelemetry/sdk-trace-node';
import { SpanMapper } from './mapper.js';
import type { CauselineSpan } from './types.js';

/** The Spring side accepts up to 2,000 spans per request; stay well below. */
const MAX_PER_REQUEST = 500;
const TIMEOUT_MS = 5_000;

/**
 * Sends Next.js spans to the Causeline in your Spring Boot app (`POST /causeline/api/peer-spans`),
 * where they join the browser's and Spring's spans in one timeline.
 */
export class CauselineSpanExporter implements SpanExporter {
  private readonly url: string;
  private stopped = false;
  private warned = false;

  /**
   * @param fetchImpl  the platform fetch, taken before Next.js patches it, so sending spans never
   *                   produces spans of its own
   * @param ownService whether a URL belongs to the application's own services (the propagation rule)
   */
  constructor(
    endpoint: string,
    private readonly token: string,
    private readonly mapper: SpanMapper,
    private readonly fetchImpl: typeof fetch,
    private readonly ownService: (url: URL) => boolean = () => true,
  ) {
    this.url = `${endpoint.replace(/\/+$/, '')}/api/peer-spans`;
  }

  export(spans: ReadableSpan[], done: (result: ExportResult) => void): void {
    if (this.stopped) {
      done({ code: ExportResultCode.FAILED });
      return;
    }
    const mapped: CauselineSpan[] = [];
    for (const span of spans) {
      if (String(span.attributes['http.url'] ?? '').startsWith(this.url) || this.isHousekeeping(span)) {
        continue;
      }
      try {
        mapped.push(...this.mapper.map(span));
      } catch {
        // A span we can't read is skipped, never thrown into Next.js.
      }
    }
    this.send(mapped).then(
      () => done({ code: ExportResultCode.SUCCESS }),
      (error: unknown) => {
        this.warnOnce(error);
        done({ code: ExportResultCode.FAILED });
      },
    );
  }

  /**
   * Our own uploads (should something else wrap fetch), and calls to third parties made outside any
   * request, such as Next.js checking npm for a newer version in development: not user actions.
   */
  private isHousekeeping(span: ReadableSpan): boolean {
    if (span.kind !== SpanKind.CLIENT || span.parentSpanContext?.spanId) {
      return false;
    }
    try {
      return !this.ownService(new URL(String(span.attributes['http.url'] ?? '')));
    } catch {
      return false;
    }
  }

  async shutdown(): Promise<void> {
    this.stopped = true;
  }

  async forceFlush(): Promise<void> {}

  private async send(spans: CauselineSpan[]): Promise<void> {
    for (let i = 0; i < spans.length; i += MAX_PER_REQUEST) {
      const response = await this.fetchImpl(this.url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Causeline-Token': this.token },
        body: JSON.stringify(spans.slice(i, i + MAX_PER_REQUEST)),
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      if (!response.ok) {
        throw new Error(
          response.status === 401
            ? 'the access token was refused; check CAUSELINE_TOKEN matches causeline.access-token'
            : `HTTP ${response.status}`,
        );
      }
    }
  }

  private warnOnce(error: unknown): void {
    if (this.warned) {
      return;
    }
    this.warned = true;
    const reason = error instanceof Error ? error.message : String(error);
    console.warn(`[causeline] could not send Next.js spans to ${this.url}: ${reason}`);
  }
}
