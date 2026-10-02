// SPDX-License-Identifier: Apache-2.0
import type { SpanRow, TraceView } from './api';
import { formatDuration } from './format';

/** The exported trace file: spans with secrets redacted by the server (SpanRedactor). */
export interface ExportedTrace {
  app?: string;
  exportedAt?: string;
  spans: { spanId: string; attributes: Record<string, string> }[];
}

const escape = (value: string) =>
  value.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] ?? c);

/**
 * A self-contained HTML bug report: timeline, insights, exceptions and span details, with no
 * scripts and no external requests, so it can be attached to a ticket and opened anywhere.
 *
 * The layout comes from the trace view; every attribute value comes from the redacted export, so
 * nothing the export would hide ends up in the report. Spans missing from the export get no
 * attributes at all rather than unredacted ones.
 */
export function buildReport(view: TraceView, exported: ExportedTrace): string {
  const redacted = new Map(exported.spans.map((s) => [s.spanId, s.attributes]));
  const total = Math.max(view.durationNanos, 1);
  const bottleneck = new Set(view.insights.filter((i) => i.rule === 'PRIMARY_BOTTLENECK').map((i) => i.spanId));

  const rows = view.spans
    .map((span) => {
      const left = (span.offsetNanos / total) * 100;
      const width = Math.max((span.durationNanos / total) * 100, 0.4);
      const color = span.status === 'ERROR' ? '#f0525a' : bottleneck.has(span.spanId) ? '#ff6a2f' : span.source === 'browser' ? '#5b86ff' : '#9b7bff';
      return `<tr><td style="padding-left:${12 + span.depth * 14}px"><span class="k">${escape(span.kind)}</span>${escape(span.name)}</td>
<td><div class="t"><div class="b" style="left:${left.toFixed(3)}%;width:${Math.min(width, 100 - left).toFixed(3)}%;background:${color}"></div></div></td>
<td class="n">${formatDuration(span.durationNanos)}</td><td class="n m">${formatDuration(span.selfNanos)}</td></tr>`;
    })
    .join('\n');

  const insights = view.insights.map((i) => `<li>${escape(i.label)}</li>`).join('');
  const details = view.spans
    .map((span) => detail(span, redacted.get(span.spanId) ?? {}))
    .filter((html) => html !== '')
    .join('\n');

  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escape(view.name)} · Causeline bug report</title>
<style>
body{margin:0;background:#0f0e0c;color:#eeeae1;font:14px/1.5 ui-sans-serif,system-ui,"Segoe UI",sans-serif}
main{max-width:1100px;margin:0 auto;padding:32px 20px}
h1{font:400 40px/1.1 Georgia,serif;margin:6px 0 10px}h2{font-size:15px;margin:28px 0 8px;color:#cfcabf}
.e{font:11px ui-monospace,Consolas,monospace;letter-spacing:.06em;text-transform:uppercase;color:#9a948a}
.chips span{display:inline-block;border:1px solid #2c2a25;border-radius:99px;padding:1px 9px;margin-right:6px;font-size:12px}
.err{color:#f0525a;border-color:#f0525a66!important}
table{width:100%;border-collapse:collapse;table-layout:fixed;border:1px solid #2c2a25;border-radius:10px}
td{padding:5px 8px;border-top:1px solid #22201c;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
td:first-child{width:42%}td.n{width:80px;text-align:right;font:12px ui-monospace,Consolas,monospace}td.m{color:#9a948a}
.k{display:inline-block;width:92px;font:10px ui-monospace,Consolas,monospace;color:#9a948a}
.t{position:relative;height:12px;background:#22201c;border-radius:6px}.b{position:absolute;top:0;bottom:0;border-radius:4px}
ul{padding-left:18px}li{margin:4px 0}
details{border:1px solid #2c2a25;border-radius:10px;padding:10px 14px;margin:8px 0}summary{cursor:pointer}
dl{display:grid;grid-template-columns:minmax(8rem,14rem) 1fr;gap:4px 14px;font-size:12px}dt{color:#9a948a;font-family:ui-monospace,Consolas,monospace}
dd{margin:0;word-break:break-all}pre{background:#171613;border:1px solid #22201c;border-radius:8px;padding:10px;white-space:pre-wrap;word-break:break-all;font-size:11.5px}
footer{margin-top:32px;color:#9a948a;font-size:12px}
</style></head><body><main>
<p class="e">Causeline bug report${exported.app ? ` · ${escape(exported.app)}` : ''} · trace ${escape(view.traceId)}</p>
<h1>${escape(view.name)}</h1>
<p class="chips"><span>${formatDuration(view.durationNanos)}</span><span>${view.spans.length} spans</span>${
    view.status === 'ERROR' ? '<span class="err">Failed</span>' : '<span>OK</span>'
  }</p>
${insights ? `<h2>Insights</h2><ul>${insights}</ul>` : ''}
<h2>Timeline</h2>
<table><tbody>
${rows}
</tbody></table>
<h2>Span details</h2>
${details || '<p class="e">No span attributes.</p>'}
<footer>Secrets redacted by Causeline's export rules${exported.exportedAt ? ` · exported ${escape(exported.exportedAt)}` : ''}.</footer>
</main></body></html>
`;
}

function detail(span: SpanRow, attributes: Record<string, string>): string {
  const entries = Object.entries(attributes);
  if (entries.length === 0) {
    return '';
  }
  const blocks = new Set([
    'exception.stacktrace',
    'http.request.body',
    'http.response.body',
    'db.query.text',
    'db.query.statement',
    'causeline.arguments',
    'causeline.return',
  ]);
  const list = entries
    .filter(([k]) => !blocks.has(k))
    .map(([k, v]) => `<dt>${escape(k)}</dt><dd>${escape(v)}</dd>`)
    .join('');
  const pres = entries
    .filter(([k]) => blocks.has(k))
    .map(([k, v]) => `<p class="e">${escape(k)}</p><pre>${escape(v)}</pre>`)
    .join('');
  return `<details${span.status === 'ERROR' || span.kind === 'EXCEPTION' ? ' open' : ''}><summary>${escape(span.kind)} · ${escape(span.name)}</summary>${
    list ? `<dl>${list}</dl>` : ''
  }${pres}</details>`;
}
