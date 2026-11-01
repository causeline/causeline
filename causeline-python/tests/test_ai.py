# SPDX-License-Identifier: Apache-2.0
import json
import re
from pathlib import Path

import causeline._ai as ai
from causeline._ai import Chunk, put_completion, put_documents, put_embedding_input, put_prompt, utf16_length
from causeline._privacy import REDACTED, Outbound
from causeline._spans import Span

JAVA = Path(__file__).resolve().parents[2] / "causeline-spring-boot/src/main/java/dev/causeline/spring/internal/ai"
OUTBOUND = Outbound(local=True)


def span():
    return Span("a" * 32, "b" * 16, None, "AI_MODEL", "chat", "ai-service")


def test_attribute_names_match_springs():
    source = (JAVA / "AiAttributes.java").read_text(encoding="utf-8")
    names = dict(re.findall(r'public static final String (\w+) = "([a-z_.]+)"', source))
    for constant in ("INPUT_MESSAGES", "OUTPUT_MESSAGES", "SYSTEM_INSTRUCTIONS", "EMBEDDING_INPUT", "SEARCH_QUERY",
                     "DOCUMENT_CONTENT", "DOCUMENTS", "DOCUMENT_COUNT", "INPUT_CHARS", "OUTPUT_CHARS", "LOW_SCORE",
                     "CONTEXT_LIMIT"):
        assert getattr(ai, constant) == names[constant]


def test_limits_match_springs():
    source = (JAVA / "AiText.java").read_text(encoding="utf-8")
    limits = {name: int(value.replace("_", "")) for name, value in re.findall(r"int (MAX_\w+) = ([\d_]+);", source)}
    assert limits == {"MAX_TEXT_CHARS": ai.MAX_TEXT_CHARS, "MAX_DOCUMENT_CHARS": ai.MAX_DOCUMENT_CHARS,
                      "MAX_DOCUMENTS": ai.MAX_DOCUMENTS, "MAX_ID_CHARS": ai.MAX_ID_CHARS}


def test_a_prompt_has_springs_shape():
    target = span()
    put_prompt(target, [("system", "Be brief"), ("user", "When?")], OUTBOUND)
    assert json.loads(target.attributes[ai.INPUT_MESSAGES]) == [
        {"role": "user", "parts": [{"type": "text", "content": "When?"}]}]
    assert json.loads(target.attributes[ai.SYSTEM_INSTRUCTIONS]) == [{"type": "text", "content": "Be brief"}]
    assert target.attributes[ai.INPUT_CHARS] == "13"


def test_a_prompt_without_a_system_message_has_no_instructions():
    target = span()
    put_prompt(target, [("user", "When?")], OUTBOUND)
    assert ai.SYSTEM_INSTRUCTIONS not in target.attributes


def test_a_completion_has_springs_shape():
    target = span()
    put_completion(target, [("Soon", "stop")], OUTBOUND)
    assert json.loads(target.attributes[ai.OUTPUT_MESSAGES]) == [
        {"role": "assistant", "parts": [{"type": "text", "content": "Soon"}], "finish_reason": "stop"}]
    assert target.attributes[ai.OUTPUT_CHARS] == "4"


def test_a_long_prompt_keeps_the_newest_message():
    target = span()
    put_prompt(target, [("user", "old" * 40_000), ("user", "new" * 30_000)], OUTBOUND)
    kept = json.loads(target.attributes[ai.INPUT_MESSAGES])
    assert len(kept[1]["parts"][0]["content"]) == 65_536
    assert kept[0]["parts"][0]["content"] == ""
    assert target.attributes[ai.INPUT_CHARS] == "210000"


def test_lengths_are_the_ones_java_and_javascript_see():
    assert utf16_length("a😀") == 3
    target = span()
    put_prompt(target, [("user", "😀")], OUTBOUND)
    assert target.attributes[ai.INPUT_CHARS] == "2"


def test_documents_keep_their_metadata_apart_from_their_text():
    target = span()
    put_documents(target, [Chunk("a", "text", 0.9, "policy.md"), Chunk("b", "more")], OUTBOUND, with_text=True)
    assert json.loads(target.attributes[ai.DOCUMENTS]) == [
        {"id": "a", "rank": 1, "chars": 4, "score": 0.9}, {"id": "b", "rank": 2, "chars": 4}]
    assert json.loads(target.attributes[ai.DOCUMENT_CONTENT]) == [
        {"text": "text", "source": "policy.md"}, {"text": "more"}]
    assert target.attributes[ai.DOCUMENT_COUNT] == "2"


def test_documents_are_limited_with_the_full_count_recorded():
    target = span()
    put_documents(target, [Chunk(str(i), "x" * 5_000) for i in range(60)], OUTBOUND, with_text=True)
    summary = json.loads(target.attributes[ai.DOCUMENTS])
    content = json.loads(target.attributes[ai.DOCUMENT_CONTENT])
    assert (len(summary), len(content)) == (50, 50)
    assert target.attributes[ai.DOCUMENT_COUNT] == "60"
    assert summary[0]["chars"] == 5_000
    assert len(content[0]["text"]) == 4_096


def test_no_document_text_without_the_switch():
    target = span()
    put_documents(target, [Chunk("a", "secret text")], OUTBOUND, with_text=False)
    assert ai.DOCUMENT_CONTENT not in target.attributes
    assert ai.DOCUMENTS in target.attributes


def test_embedding_input_is_a_list_of_texts():
    target = span()
    put_embedding_input(target, ["one", "two"], OUTBOUND)
    assert json.loads(target.attributes[ai.EMBEDDING_INPUT]) == ["one", "two"]
    assert target.attributes[ai.INPUT_CHARS] == "6"


def test_redact_keys_hide_values_inside_ai_text_even_locally():
    outbound = Outbound(local=True, redact_keys=("employee_id",))
    target = span()
    put_prompt(target, [("user", "employee_id=12345 asks about refunds")], outbound)
    assert "12345" not in target.attributes[ai.INPUT_MESSAGES]
    assert REDACTED in target.attributes[ai.INPUT_MESSAGES]


def test_built_in_keys_stay_visible_locally_in_ai_text():
    target = span()
    put_prompt(target, [("user", "password=hunter2")], OUTBOUND)
    assert "hunter2" in target.attributes[ai.INPUT_MESSAGES]
