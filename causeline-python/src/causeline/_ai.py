# SPDX-License-Identifier: Apache-2.0
"""
AI attributes in the exact shapes Spring's capture filters write, so rag.ts and AiChecks need no
Python case. Knows nothing about LangChain.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Sequence

from ._privacy import Outbound
from ._spans import Span

MAX_TEXT_CHARS = 65_536
MAX_DOCUMENT_CHARS = 4_096
MAX_DOCUMENTS = 50
MAX_ID_CHARS = 200

INPUT_MESSAGES = "gen_ai.input.messages"
OUTPUT_MESSAGES = "gen_ai.output.messages"
SYSTEM_INSTRUCTIONS = "gen_ai.system_instructions"
EMBEDDING_INPUT = "causeline.ai.embedding.input"
SEARCH_QUERY = "db.vector.query.content"
DOCUMENTS = "causeline.ai.documents"
DOCUMENT_CONTENT = "causeline.ai.documents.content"
DOCUMENT_COUNT = "causeline.ai.documents.count"
INPUT_CHARS = "causeline.ai.input.chars"
OUTPUT_CHARS = "causeline.ai.output.chars"
LOW_SCORE = "causeline.ai.low_score"
CONTEXT_LIMIT = "causeline.ai.context_limit"


@dataclass(frozen=True)
class Chunk:
    id: str
    text: str
    score: float | None = None
    source: str | None = None


def utf16_length(text: str) -> int:
    """The length Java and JavaScript see. The UI compares it with what it received to spot a cut."""
    return len(text.encode("utf-16-le", "surrogatepass")) // 2


def _dumps(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _message(role: str, content: str, finish_reason: str | None = None) -> dict[str, object]:
    message: dict[str, object] = {"role": role, "parts": [{"type": "text", "content": content}]}
    if finish_reason is not None:
        message["finish_reason"] = finish_reason
    return message


def put_prompt(span: Span, messages: Sequence[tuple[str, str]], outbound: Outbound) -> None:
    system = [text for role, text in messages if role == "system"]
    others = [(role, text) for role, text in messages if role != "system"]
    # The newest message holds the question and its context, so it is the last to lose space.
    budget = MAX_TEXT_CHARS
    kept: list[dict[str, object]] = []
    for role, text in reversed(others):
        clean = outbound.clean(text, budget)
        budget = max(0, budget - len(clean))
        kept.insert(0, _message(role, clean))
    span.put(INPUT_MESSAGES, _dumps(kept))
    if system:
        budget = MAX_TEXT_CHARS
        instructions = []
        for text in system:
            clean = outbound.clean(text, budget)
            budget = max(0, budget - len(clean))
            instructions.append({"type": "text", "content": clean})
        span.put(SYSTEM_INSTRUCTIONS, _dumps(instructions))
    span.put(INPUT_CHARS, sum(utf16_length(text) for _, text in messages))


def put_completion(span: Span, answers: Sequence[tuple[str, str | None]], outbound: Outbound) -> None:
    budget = MAX_TEXT_CHARS
    kept = []
    for text, finish_reason in answers:
        clean = outbound.clean(text, budget)
        budget = max(0, budget - len(clean))
        kept.append(_message("assistant", clean, finish_reason))
    span.put(OUTPUT_MESSAGES, _dumps(kept))
    span.put(OUTPUT_CHARS, sum(utf16_length(text) for text, _ in answers))


def put_embedding_input(span: Span, inputs: Sequence[str], outbound: Outbound) -> None:
    budget = MAX_TEXT_CHARS
    kept = []
    for text in inputs:
        clean = outbound.clean(text, budget)
        budget = max(0, budget - len(clean))
        kept.append(clean)
    span.put(EMBEDDING_INPUT, _dumps(kept))
    span.put(INPUT_CHARS, sum(utf16_length(text) for text in inputs))


def put_documents(span: Span, chunks: Sequence[Chunk], outbound: Outbound, *, with_text: bool) -> None:
    """IDs, ranks, scores and lengths always; the text only when documents are captured."""
    span.put(DOCUMENT_COUNT, len(chunks))
    summary = []
    content = []
    for rank, chunk in enumerate(chunks[:MAX_DOCUMENTS], start=1):
        entry: dict[str, object] = {"id": chunk.id[:MAX_ID_CHARS], "rank": rank, "chars": utf16_length(chunk.text)}
        if chunk.score is not None:
            entry["score"] = chunk.score
        summary.append(entry)
        body: dict[str, object] = {"text": outbound.clean(chunk.text, MAX_DOCUMENT_CHARS)}
        if chunk.source is not None:
            body["source"] = outbound.clean(chunk.source, MAX_ID_CHARS)
        content.append(body)
    span.put(DOCUMENTS, _dumps(summary))
    if with_text:
        span.put(DOCUMENT_CONTENT, _dumps(content))
