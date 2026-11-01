// SPDX-License-Identifier: Apache-2.0
import { useState } from 'react';
import type { ReactNode } from 'react';
import type { SpanRow } from './api';
import { scoreShare, tokenUsage } from './rag';
import type { RagCall, RagChunk, RagMessage, RagSearch, RagView } from './rag';
import type { RagRunDiff } from './ragDiff';

function Step({ title, note, children }: { title: string; note?: ReactNode; children: ReactNode }) {
  return (
    <section aria-label={title} className="border-t border-line-soft pt-3">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
        <span className="eyebrow">{title}</span>
        {note && <span className="text-[11px] text-muted">{note}</span>}
      </div>
      <div className="mt-2">{children}</div>
    </section>
  );
}

function Ref({ value }: { value: number }) {
  return (
    <span className="mr-1 inline-grid h-4 min-w-4 place-items-center rounded-sm bg-server px-1 align-[1px] font-mono text-[10px] leading-none font-semibold text-paper">
      {value}
    </span>
  );
}

const PLACEMENT = {
  'in-prompt': { label: 'In prompt', tone: 'border-ok/40 text-ok', title: 'This text is in the prompt sent to the model' },
  'not-in-prompt': {
    label: 'Not in prompt',
    tone: 'border-warn/40 text-warn',
    title: 'Retrieved, but this text is not in the prompt: a reranker removed it, or a template rewrote it',
  },
} as const;

function ChunkCard({ chunk }: { chunk: RagChunk }) {
  const [open, setOpen] = useState(false);
  const placement = chunk.placement === 'unknown' ? undefined : PLACEMENT[chunk.placement];
  const kept = chunk.keptChars;
  const long = kept > 180;
  return (
    <li className="rounded-lg border border-line-soft bg-paper-2 px-3 py-2">
      <div className="flex min-w-0 items-center gap-2">
        <Ref value={chunk.ref} />
        <span className="min-w-0 truncate font-mono text-xs text-ink" title={chunk.id}>
          {chunk.id}
        </span>
        {chunk.source && <span className="min-w-0 truncate text-[11px] text-muted">{chunk.source}</span>}
        {placement && (
          <span className={`chip ml-auto ${placement.tone}`} title={placement.title}>
            {placement.label}
          </span>
        )}
      </div>
      {chunk.score !== undefined && (
        <div className="mt-1.5 flex items-center gap-2">
          <div aria-hidden className="h-1 flex-1 overflow-hidden rounded-full bg-line">
            <div className="h-full rounded-full bg-server" style={{ width: `${scoreShare(chunk.score) * 100}%` }} />
          </div>
          <span className="font-mono text-[11px] text-ink-2" title="Similarity score from the vector store">
            {Math.min(chunk.score, 1).toFixed(3)}
          </span>
        </div>
      )}
      {chunk.text !== undefined && (
        <p className={`mt-1.5 text-xs leading-relaxed whitespace-pre-wrap text-ink-2 ${open ? '' : 'line-clamp-2'}`}>
          {chunk.text}
        </p>
      )}
      {long && (
        <button type="button" onClick={() => setOpen(!open)} className="mt-1 text-[11px] text-muted hover:text-ink">
          {open ? 'Show less' : 'Show all'}
        </button>
      )}
      {chunk.chars > kept && kept > 0 && (
        <p className="mt-1 text-[11px] text-muted">
          First {kept.toLocaleString()} of {chunk.chars.toLocaleString()} characters
        </p>
      )}
    </li>
  );
}

function SearchStep({ search, label }: { search: RagSearch; label: string }) {
  const facts = [
    search.store,
    search.collection,
    search.topK && `top ${search.topK}`,
    search.threshold && Number(search.threshold) > 0 ? `threshold ${search.threshold}` : undefined,
  ].filter(Boolean);
  return (
    <div className="min-w-0">
      <Step title={label} note={facts.join(' · ')}>
        {search.query !== undefined ? (
          <p className="rounded-lg border border-line-soft bg-paper-2 px-3 py-2 text-[13px] break-words text-ink">{search.query}</p>
        ) : (
          <p className="text-xs text-muted">The query text was not captured, or was withheld on export.</p>
        )}
        {search.filter && <p className="mt-1.5 font-mono text-[11px] break-all text-muted">filter: {search.filter}</p>}
      </Step>
      <div className="mt-3">
        <Step
          title={`${search.count} ${search.count === 1 ? 'chunk' : 'chunks'}`}
          note={search.count > search.chunks.length ? `first ${search.chunks.length} kept` : undefined}
        >
          {search.chunks.length === 0 ? (
            <p className="text-xs text-warn">Nothing came back, so the model had no context from this search.</p>
          ) : (
            <ol className="space-y-1.5">
              {search.chunks.map((chunk) => (
                <ChunkCard key={`${chunk.rank}-${chunk.id}`} chunk={chunk} />
              ))}
            </ol>
          )}
          {search.textWithheld && <p className="mt-1.5 text-xs text-muted">Chunk text is withheld on export.</p>}
        </Step>
      </div>
    </div>
  );
}

function Message({ message }: { message: RagMessage }) {
  return (
    <div className="rounded-lg border border-line-soft bg-paper-2 px-3 py-2">
      <div className="eyebrow">{message.role}</div>
      <p className="mt-1 max-h-96 overflow-auto text-xs leading-relaxed break-words whitespace-pre-wrap text-ink-2">
        {message.segments.map((segment, i) =>
          segment.ref === undefined ? (
            segment.text
          ) : (
            <mark key={i} className="rounded-sm bg-server/20 text-ink">
              <Ref value={segment.ref} />
              {segment.text}
            </mark>
          ),
        )}
      </p>
    </div>
  );
}

function answerNote(call: RagCall): string {
  if (call.answerWithheld) {
    return 'The answer is withheld on export.';
  }
  return call.span.status === 'ERROR' ? 'The call failed before an answer came back.' : 'No answer recorded: the stream was cancelled, or answers aren’t captured.';
}

function CallSteps({ call }: { call: RagCall }) {
  const reason = call.answers[0]?.finishReason;
  const stoppedEarly = reason !== undefined && ['length', 'max_tokens'].includes(reason.toLowerCase());
  return (
    <>
      <Step title="Prompt" note={call.promptCut ? 'Cut: only the start is kept' : undefined}>
        {call.messages.length > 0 ? (
          <div className="space-y-1.5">
            {call.messages.map((message, i) => (
              <Message key={i} message={message} />
            ))}
          </div>
        ) : (
          <p className="text-xs text-muted">
            {call.promptWithheld ? 'The prompt is withheld on export.' : 'The prompt was not captured.'}
          </p>
        )}
      </Step>
      <Step
        title="Answer"
        note={
          reason && (
            <span className={stoppedEarly ? 'text-warn' : ''}>
              {stoppedEarly ? 'Cut off by the output limit' : `Finished: ${reason.toLowerCase()}`}
            </span>
          )
        }
      >
        {call.answers.length > 0 ? (
          call.answers.map((answer, i) => (
            <p key={i} className="rounded-lg border border-line-soft bg-paper-2 px-3 py-2 text-[13px] leading-relaxed whitespace-pre-wrap text-ink">
              {answer.text}
            </p>
          ))
        ) : (
          <p className="text-xs text-muted">{answerNote(call)}</p>
        )}
        {call.answerCut && <p className="mt-1 text-[11px] text-muted">Only the start is kept.</p>}
      </Step>
    </>
  );
}

const signed = (value: number) => `${value > 0 ? '+' : ''}${value.toLocaleString()}`;

function RunChanges({ run, left, right }: { run: RagRunDiff; left: string; right: string }) {
  const tokens = run.tokens;
  return (
    <div>
      <p className="font-medium text-ink-2">
        {run.name}: <span className="font-normal text-muted">{run.same ? 'same chunks, scores and tokens' : 'changed'}</span>
      </p>
      {!run.same && (
        <ul className="mt-1 space-y-0.5 font-mono">
          {run.added.map((id) => (
            <li key={`added-${id}`} className="text-ok">
              + {id}
            </li>
          ))}
          {run.removed.map((id) => (
            <li key={`removed-${id}`} className="text-error">
              − {id}
            </li>
          ))}
          {run.moved.map((move) => (
            <li key={`moved-${move.id}`} className="text-ink-2">
              {move.id}: rank {move.from} → {move.to}
            </li>
          ))}
          {run.scores.map((score) => (
            <li key={`score-${score.id}`} className="text-ink-2">
              {score.id}: score {score.before.toFixed(3)} → {score.after.toFixed(3)}
            </li>
          ))}
          {tokens && (
            <li className="text-ink-2">
              tokens: {signed(tokens.input)} in, {signed(tokens.output)} out
            </li>
          )}
        </ul>
      )}
      {(run.answers.before !== undefined || run.answers.after !== undefined) && (
        <div className="mt-2 grid grid-cols-2 gap-2">
          {[
            { side: left, text: run.answers.before },
            { side: right, text: run.answers.after },
          ].map(({ side, text }) => (
            <div key={side} className="rounded-lg border border-line-soft bg-paper-2 px-2 py-1.5">
              <div className="eyebrow">Answer · {side}</div>
              <p className="mt-1 whitespace-pre-wrap text-ink-2">{text ?? 'No answer recorded'}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/** What changed in retrieval and tokens between two runs. Answers sit side by side but never count as a change. */
export function RagChanges({ runs, left, right }: { runs: RagRunDiff[]; left: string; right: string }) {
  return (
    <div className="mt-3 space-y-3 text-xs" aria-label="RAG changes">
      {runs.map((run, i) => (
        <RunChanges key={i} run={run} left={left} right={right} />
      ))}
    </div>
  );
}

/** Input, cached and output tokens as one bar. Costs aren't shown: Causeline keeps no price list. */
export function TokenBar({ span }: { span: SpanRow }) {
  const usage = tokenUsage(span);
  if (!usage) {
    return null;
  }
  const input = usage.input ?? 0;
  const output = usage.output ?? 0;
  const cached = Math.min(usage.cached ?? 0, input);
  const whole = Math.max(input + output, 1);
  const share = (tokens: number) => `${(tokens / whole) * 100}%`;
  return (
    <div className="mt-4" role="img" aria-label={`${input} input tokens, ${output} output tokens`}>
      <div aria-hidden className="flex h-1.5 gap-px overflow-hidden rounded-full">
        {cached > 0 && <div className="bg-muted" style={{ width: share(cached) }} />}
        <div className="bg-server" style={{ width: share(input - cached) }} />
        <div className="bg-signal" style={{ width: share(output) }} />
      </div>
      <div className="mt-1.5 flex flex-wrap gap-x-4 font-mono text-[11px] text-muted">
        <span>
          <span className="text-ink">{input.toLocaleString()}</span> in
        </span>
        {cached > 0 && (
          <span>
            <span className="text-ink">{cached.toLocaleString()}</span> cached
          </span>
        )}
        <span>
          <span className="text-ink">{output.toLocaleString()}</span> out
        </span>
        {usage.total !== undefined && (
          <span>
            <span className="text-ink">{usage.total.toLocaleString()}</span> total
          </span>
        )}
      </div>
    </div>
  );
}

/** One retrieval and model call as a pipeline: what was searched, what came back, what reached the model. */
export function RagPanel({ view, narrow = false }: { view: RagView; narrow?: boolean }) {
  const several = view.searches.length > 1;
  return (
    <div aria-label="RAG pipeline" role="group" className="mt-4 space-y-3">
      {view.searches.length > 0 && (
        <div className={several && !narrow ? 'grid grid-cols-2 gap-3' : 'space-y-3'}>
          {view.searches.map((search, i) => (
            <SearchStep key={search.span.spanId} search={search} label={several ? `Search ${i + 1}` : 'Search'} />
          ))}
        </div>
      )}
      {view.call && <CallSteps call={view.call} />}
    </div>
  );
}
