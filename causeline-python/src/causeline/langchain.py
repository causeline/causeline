# SPDX-License-Identifier: Apache-2.0
"""
LangChain calls as spans. ``causeline.init`` installs the handler, so application code changes
nothing. LangChain's callbacks give no chunk scores and no embedding calls, so those two come from
the opt-in :func:`scored_retriever` and :func:`traced_embeddings`.
"""
from __future__ import annotations

import asyncio
import functools
import hashlib
import json
import logging
import math
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any, Callable, Iterator, Sequence
from uuid import UUID

from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_core.retrievers import BaseRetriever
from langchain_core.tracers.context import register_configure_hook

from . import _ai, _runtime
from ._ai import Chunk
from ._spans import Span, current_span, new_trace_id

logger = logging.getLogger("causeline")

_ROLES = {"human": "user", "ai": "assistant"}
_SCORE_KEYS = ("score", "relevance_score")
# A cancelled run may never end, so this can't be allowed to grow for ever.
_MAX_OPEN_RUNS = 10_000


def _guarded(method: Callable[..., None]) -> Callable[..., None]:
    """Skips the work when Causeline is off. A failure loses the value, never the application's call."""
    @functools.wraps(method)
    def wrapper(self: "CauselineCallbackHandler", *args: Any, **kwargs: Any) -> None:
        recorder = _runtime.active()
        if recorder is None:
            return
        try:
            method(self, recorder, *args, **kwargs)
        except Exception:
            logger.debug("Causeline could not read a LangChain event", exc_info=True)
    return wrapper


class _Run:
    """Where a run's children go. Chains aren't spans, so they pass on the place they were given."""
    __slots__ = ("trace_id", "parent_span_id", "span")

    def __init__(self, trace_id: str, parent_span_id: str | None, span: Span | None = None) -> None:
        self.trace_id = trace_id
        self.parent_span_id = parent_span_id
        self.span = span


class CauselineCallbackHandler(BaseCallbackHandler):
    # Reading the event is cheap, and this keeps it off LangChain's thread pool.
    run_inline = True

    def __init__(self) -> None:
        self._runs: dict[UUID, _Run] = {}

    def _place(self, parent_run_id: UUID | None) -> _Run:
        parent = self._runs.get(parent_run_id) if parent_run_id else None
        if parent is not None:
            return parent
        current = current_span.get()
        if current is not None:
            return _Run(current.trace_id, current.span_id)
        # No request and no parent run: a script, or ingestion at startup.
        return _Run(new_trace_id(), None)

    def _remember(self, run_id: UUID, run: _Run) -> None:
        if len(self._runs) >= _MAX_OPEN_RUNS:
            self._runs.pop(next(iter(self._runs)), None)
        self._runs[run_id] = run

    def _open(self, recorder: _runtime.Recorder, kind: str, name: str, run_id: UUID, parent_run_id: UUID | None) -> Span:
        place = self._place(parent_run_id)
        span = recorder.start(kind, name, trace_id=place.trace_id, parent_span_id=place.parent_span_id)
        self._remember(run_id, _Run(span.trace_id, span.span_id, span))
        return span

    def _span(self, run_id: UUID) -> Span | None:
        run = self._runs.pop(run_id, None)
        return run.span if run is not None else None

    def _fail(self, recorder: _runtime.Recorder, run_id: UUID, error: BaseException) -> None:
        span = self._span(run_id)
        if span is None:
            return
        # A caller that stopped reading isn't a failure. The span keeps its prompt and has no answer.
        if not isinstance(error, (GeneratorExit, asyncio.CancelledError)):
            recorder.exception(span, error)
            span.status = "ERROR"
        recorder.end(span)

    @_guarded
    def on_chain_start(self, recorder: _runtime.Recorder, serialized: Any, inputs: Any, *, run_id: UUID,
                       parent_run_id: UUID | None = None, **kwargs: Any) -> None:
        self._remember(run_id, self._place(parent_run_id))

    @_guarded
    def on_chain_end(self, recorder: _runtime.Recorder, outputs: Any, *, run_id: UUID, **kwargs: Any) -> None:
        self._runs.pop(run_id, None)

    @_guarded
    def on_chain_error(self, recorder: _runtime.Recorder, error: BaseException, *, run_id: UUID, **kwargs: Any) -> None:
        self._runs.pop(run_id, None)

    @_guarded
    def on_chat_model_start(self, recorder: _runtime.Recorder, serialized: Any, messages: list[list[Any]], *,
                            run_id: UUID, parent_run_id: UUID | None = None, metadata: dict[str, Any] | None = None,
                            **kwargs: Any) -> None:
        span = self._open_chat(recorder, run_id, parent_run_id, metadata)
        if "prompts" in recorder.settings.capture and messages:
            turns = [(_ROLES.get(message.type, message.type), _message_text(message)) for message in messages[0]]
            _ai.put_prompt(span, turns, recorder.outbound)

    @_guarded
    def on_llm_start(self, recorder: _runtime.Recorder, serialized: Any, prompts: list[str], *, run_id: UUID,
                     parent_run_id: UUID | None = None, metadata: dict[str, Any] | None = None, **kwargs: Any) -> None:
        span = self._open_chat(recorder, run_id, parent_run_id, metadata)
        if "prompts" in recorder.settings.capture and prompts:
            _ai.put_prompt(span, [("user", prompts[0])], recorder.outbound)

    def _open_chat(self, recorder: _runtime.Recorder, run_id: UUID, parent_run_id: UUID | None,
                   metadata: dict[str, Any] | None) -> Span:
        details = metadata or {}
        model = details.get("ls_model_name")
        span = self._open(recorder, "AI_MODEL", f"chat {model}" if model else "chat", run_id, parent_run_id)
        span.put("gen_ai.operation.name", "chat")
        span.put("gen_ai.system", details.get("ls_provider"))
        span.put("gen_ai.request.model", model)
        span.put("gen_ai.request.temperature", details.get("ls_temperature"))
        span.put("gen_ai.request.max_tokens", details.get("ls_max_tokens"))
        limit = recorder.settings.context_limit
        if limit:
            span.put(_ai.CONTEXT_LIMIT, limit)
        return span

    @_guarded
    def on_llm_end(self, recorder: _runtime.Recorder, response: Any, *, run_id: UUID, **kwargs: Any) -> None:
        span = self._span(run_id)
        if span is None:
            return
        generations = response.generations[0] if response.generations else []
        first = generations[0] if generations else None
        message = getattr(first, "message", None)
        details = getattr(message, "response_metadata", None) or {}
        _put_usage(span, getattr(message, "usage_metadata", None))
        span.put("gen_ai.response.model", details.get("model_name") or details.get("model"))
        answers = [(generation.text, _finish_reason(generation)) for generation in generations]
        reasons = [reason for _, reason in answers if reason]
        if reasons:
            span.put("gen_ai.response.finish_reasons", json.dumps(reasons))
        if "completions" in recorder.settings.capture:
            _ai.put_completion(span, answers, recorder.outbound)
        recorder.end(span)

    @_guarded
    def on_llm_error(self, recorder: _runtime.Recorder, error: BaseException, *, run_id: UUID, **kwargs: Any) -> None:
        self._fail(recorder, run_id, error)

    @_guarded
    def on_retriever_start(self, recorder: _runtime.Recorder, serialized: Any, query: str, *, run_id: UUID,
                           parent_run_id: UUID | None = None, metadata: dict[str, Any] | None = None,
                           **kwargs: Any) -> None:
        details = metadata or {}
        store = details.get("ls_vector_store_provider") or kwargs.get("name") or "retriever"
        span = self._open(recorder, "AI_RETRIEVAL", f"{store} query", run_id, parent_run_id)
        span.put("db.system", store)
        span.put("db.operation.name", "query")
        span.put("db.vector.query.top_k", details.get("causeline_top_k"))
        low_score = recorder.settings.low_score
        if low_score > 0:
            span.put(_ai.LOW_SCORE, low_score)
        if "documents" in recorder.settings.capture:
            span.put(_ai.SEARCH_QUERY, recorder.outbound.clean(query, _ai.MAX_TEXT_CHARS))

    @_guarded
    def on_retriever_end(self, recorder: _runtime.Recorder, documents: Sequence[Document], *, run_id: UUID,
                         **kwargs: Any) -> None:
        span = self._span(run_id)
        if span is not None:
            _ai.put_documents(span, [_chunk(document) for document in documents], recorder.outbound,
                              with_text="documents" in recorder.settings.capture)
            recorder.end(span)

    @_guarded
    def on_retriever_error(self, recorder: _runtime.Recorder, error: BaseException, *, run_id: UUID,
                           **kwargs: Any) -> None:
        self._fail(recorder, run_id, error)


def _message_text(message: Any) -> str:
    """A message's text. Multimodal content is a list of parts, and only the text parts are kept."""
    content = message.content
    if isinstance(content, str):
        return content
    parts = (part if isinstance(part, str) else part.get("text", "") for part in content
             if isinstance(part, str) or (isinstance(part, dict) and part.get("type") == "text"))
    return "".join(parts)


def _finish_reason(generation: Any) -> str | None:
    info = getattr(generation, "generation_info", None) or {}
    details = getattr(getattr(generation, "message", None), "response_metadata", None) or {}
    # Anthropic calls it a stop reason.
    return (info.get("finish_reason") or details.get("finish_reason") or info.get("stop_reason")
            or details.get("stop_reason"))


def _put_usage(span: Span, usage: dict[str, Any] | None) -> None:
    if not usage:
        return
    span.put("gen_ai.usage.input_tokens", usage.get("input_tokens"))
    span.put("gen_ai.usage.output_tokens", usage.get("output_tokens"))
    span.put("gen_ai.usage.total_tokens", usage.get("total_tokens"))
    span.put("gen_ai.usage.cache_read.input_tokens", (usage.get("input_token_details") or {}).get("cache_read"))


def _chunk(document: Document) -> Chunk:
    text = document.page_content or ""
    source = document.metadata.get("source")
    return Chunk(
        # Comparison pairs documents by ID, so one without an ID needs one that survives between runs.
        id=str(document.id) if document.id else hashlib.sha256(text.encode("utf-8", "replace")).hexdigest()[:16],
        text=text,
        score=_score(document.metadata),
        source=source if isinstance(source, str) else None,
    )


def _score(metadata: dict[str, Any]) -> float | None:
    for key in _SCORE_KEYS:
        value = metadata.get(key)
        if value is None or isinstance(value, bool):
            continue
        try:
            number = float(value)
        except (TypeError, ValueError):
            continue
        if math.isfinite(number):
            return number
    return None


class _ScoredRetriever(BaseRetriever):
    store: Any
    k: int = 4

    def _get_ls_params(self, **kwargs: Any) -> dict[str, Any]:
        return {"ls_retriever_name": "vectorstore", "ls_vector_store_provider": type(self.store).__name__,
                "causeline_top_k": self.k}

    def _get_relevant_documents(self, query: str, *, run_manager: Any) -> list[Document]:
        try:
            pairs = self.store.similarity_search_with_relevance_scores(query, k=self.k)
        except NotImplementedError:
            pairs = self.store.similarity_search_with_score(query, k=self.k)
        return [_with_score(document, score) for document, score in pairs]

    async def _aget_relevant_documents(self, query: str, *, run_manager: Any) -> list[Document]:
        try:
            pairs = await self.store.asimilarity_search_with_relevance_scores(query, k=self.k)
        except NotImplementedError:
            pairs = await self.store.asimilarity_search_with_score(query, k=self.k)
        return [_with_score(document, score) for document, score in pairs]


def _with_score(document: Document, score: float) -> Document:
    return document.model_copy(update={"metadata": {**document.metadata, "score": score}})


def scored_retriever(store: Any, k: int = 4) -> BaseRetriever:
    """
    A retriever over a vector store that records each document's score in its metadata, since
    LangChain's own retrievers drop it. A store with no relevance function gives its raw score,
    which may be a distance, where lower is better.
    """
    return _ScoredRetriever(store=store, k=k)


class _TracedEmbeddings(Embeddings):
    def __init__(self, embeddings: Embeddings) -> None:
        self._embeddings = embeddings

    def __getattr__(self, name: str) -> Any:
        return getattr(self._embeddings, name)

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        with self._traced(texts):
            return self._embeddings.embed_documents(texts)

    def embed_query(self, text: str) -> list[float]:
        with self._traced([text]):
            return self._embeddings.embed_query(text)

    async def aembed_documents(self, texts: list[str]) -> list[list[float]]:
        with self._traced(texts):
            return await self._embeddings.aembed_documents(texts)

    async def aembed_query(self, text: str) -> list[float]:
        with self._traced([text]):
            return await self._embeddings.aembed_query(text)

    @contextmanager
    def _traced(self, inputs: Sequence[str]) -> Iterator[None]:
        span = self._open(inputs)
        error = None
        try:
            yield
        except Exception as failure:
            error = failure
            raise
        finally:
            _close(span, error)

    def _open(self, inputs: Sequence[str]) -> Span | None:
        recorder = _runtime.active()
        if recorder is None:
            return None
        try:
            model = getattr(self._embeddings, "model", None) or getattr(self._embeddings, "model_name", None)
            current = current_span.get()
            span = recorder.start("AI_MODEL", f"embedding {model}" if model else "embedding",
                                  trace_id=current.trace_id if current else None,
                                  parent_span_id=current.span_id if current else None)
            span.put("gen_ai.operation.name", "embedding")
            span.put("gen_ai.request.model", model)
            capture = recorder.settings.capture
            # The input is a question, or a whole document while ingesting.
            if "prompts" in capture and "documents" in capture:
                _ai.put_embedding_input(span, inputs, recorder.outbound)
            return span
        except Exception:
            logger.debug("Causeline could not read an embedding call", exc_info=True)
            return None


def _close(span: Span | None, error: Exception | None) -> None:
    recorder = _runtime.active()
    if span is None or recorder is None:
        return
    try:
        if error is not None:
            recorder.exception(span, error)
            span.status = "ERROR"
        recorder.end(span)
    except Exception:
        logger.debug("Causeline could not end an embedding span", exc_info=True)


def traced_embeddings(embeddings: Embeddings) -> Embeddings:
    """Wraps an embedding model so its calls show as spans. LangChain has no callback for them."""
    return _TracedEmbeddings(embeddings)


_handler = CauselineCallbackHandler()
# A default, not a value set here, because a thread or task started elsewhere has its own context.
_handler_var: ContextVar[CauselineCallbackHandler | None] = ContextVar("causeline_handler", default=_handler)
_installed = False


def install() -> None:
    """Adds the handler to every LangChain run in this process. Safe to call twice."""
    global _installed
    if not _installed:
        register_configure_hook(_handler_var, inheritable=True)
        _installed = True


__all__ = ["CauselineCallbackHandler", "install", "scored_retriever", "traced_embeddings"]
