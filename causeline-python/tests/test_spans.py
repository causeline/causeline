# SPDX-License-Identifier: Apache-2.0
import re

import pytest

from causeline._spans import Span, exception_span, new_span_id, new_trace_id, parse_traceparent

TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
PARENT = "00f067aa0ba902b7"


def test_reads_a_traceparent():
    assert parse_traceparent(f"00-{TRACE}-{PARENT}-01") == (TRACE, PARENT)


def test_keeps_the_trace_of_an_unsampled_caller():
    assert parse_traceparent(f"00-{TRACE}-{PARENT}-00") == (TRACE, PARENT)


def test_accepts_a_later_version_with_more_fields():
    assert parse_traceparent(f"01-{TRACE}-{PARENT}-01-extra") == (TRACE, PARENT)


@pytest.mark.parametrize("header", [
    None,
    "",
    "nonsense",
    f"ff-{TRACE}-{PARENT}-01",
    f"00-{'0' * 32}-{PARENT}-01",
    f"00-{TRACE}-{'0' * 16}-01",
    f"00-{TRACE.upper()}-{PARENT}-01",
    f"00-{TRACE[:-1]}-{PARENT}-01",
])
def test_refuses_an_invalid_traceparent(header):
    assert parse_traceparent(header) is None


def test_ids_have_the_lengths_spring_requires():
    assert re.fullmatch(r"[0-9a-f]{32}", new_trace_id())
    assert re.fullmatch(r"[0-9a-f]{16}", new_span_id())


def test_a_span_serialises_to_the_schema_spring_reads():
    span = Span(TRACE, PARENT, None, "REQUEST", "GET /", "ai-service")
    span.finish()
    body = span.to_json({"a": "b"})
    assert set(body) == {"traceId", "spanId", "parentSpanId", "kind", "name", "source", "startTimeUnixNano",
                         "durationNanos", "status", "attributes"}
    assert body["attributes"] == {"a": "b"}
    assert body["durationNanos"] >= 0


def test_an_empty_value_is_not_recorded():
    span = Span(TRACE, PARENT, None, "REQUEST", "GET /", "ai-service")
    span.put("a", None)
    span.put("b", "")
    span.put("c", 0)
    assert span.attributes == {"c": "0"}


def owner():
    return Span(TRACE, PARENT, None, "REQUEST", "GET /", "ai-service")


def test_an_exception_span_names_where_it_was_raised():
    try:
        raise ValueError("no such order")
    except ValueError as error:
        span = exception_span(owner(), error)
    assert span.kind == "EXCEPTION"
    assert span.parent_span_id == PARENT
    assert span.name == "ValueError"
    assert span.status == "ERROR"
    assert span.attributes["exception.message"] == "no such order"
    assert __file__ in span.attributes["code.location"]
    assert int(span.attributes["code.lineno"]) > 0


def test_library_frames_are_not_the_applications_location():
    import json
    try:
        json.loads("{")
    except ValueError as error:
        span = exception_span(owner(), error)
    assert __file__ in span.attributes["code.location"]


def test_a_long_stack_keeps_its_end():
    def deep(depth):
        if depth == 0:
            raise ValueError("the end")
        deep(depth - 1)

    try:
        deep(300)
    except ValueError as error:
        span = exception_span(owner(), error)
    stack = span.attributes["exception.stacktrace"]
    assert len(stack) <= 8_001
    assert stack.endswith("ValueError: the end\n")
