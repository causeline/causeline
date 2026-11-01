# SPDX-License-Identifier: Apache-2.0
"""Spans in Causeline's schema, as Spring accepts them on /causeline/api/peer-spans."""
from __future__ import annotations

import os
import re
import time
import traceback
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any

_TRACEPARENT = re.compile(r"^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}(-.*)?$")
_MAX_STACK_CHARS = 8_000
_STDLIB = os.path.normcase(os.path.dirname(os.__file__))
_THIS_PACKAGE = os.path.normcase(os.path.dirname(__file__))


def new_trace_id() -> str:
    return os.urandom(16).hex()


def new_span_id() -> str:
    return os.urandom(8).hex()


def parse_traceparent(header: str | None) -> tuple[str, str] | None:
    """The trace and parent span IDs of a W3C traceparent header, or None if it isn't a valid one."""
    match = _TRACEPARENT.match(header.strip()) if header else None
    if match is None or match.group(1) == "ff":
        return None
    trace_id, span_id = match.group(2), match.group(3)
    if set(trace_id) == {"0"} or set(span_id) == {"0"}:
        return None
    return trace_id, span_id


@dataclass(slots=True)
class Span:
    trace_id: str
    span_id: str
    parent_span_id: str | None
    kind: str
    name: str
    source: str
    start_time_unix_nano: int = field(default_factory=time.time_ns)
    duration_nanos: int = 0
    status: str = "OK"
    attributes: dict[str, str] = field(default_factory=dict)
    # The wall clock can jump, so durations come from the monotonic one.
    started: int = field(default_factory=time.perf_counter_ns)

    def put(self, key: str, value: Any) -> None:
        if value is not None and value != "":
            self.attributes[key] = str(value)

    def finish(self) -> None:
        self.duration_nanos = max(0, time.perf_counter_ns() - self.started)

    def to_json(self, attributes: dict[str, str]) -> dict[str, Any]:
        return {
            "traceId": self.trace_id,
            "spanId": self.span_id,
            "parentSpanId": self.parent_span_id,
            "kind": self.kind,
            "name": self.name,
            "source": self.source,
            "startTimeUnixNano": self.start_time_unix_nano,
            "durationNanos": self.duration_nanos,
            "status": self.status,
            "attributes": attributes,
        }


current_span: ContextVar[Span | None] = ContextVar("causeline_span", default=None)


def exception_span(owner: Span, error: BaseException) -> Span:
    """A zero-duration span under the span the exception passed through."""
    kind_name = type(error).__name__
    span = Span(owner.trace_id, new_span_id(), owner.span_id, "EXCEPTION", kind_name, owner.source, status="ERROR")
    span.put("exception.type", kind_name)
    span.put("exception.message", str(error))
    stack = "".join(traceback.format_exception(type(error), error, error.__traceback__))
    # The end of a Python stack is where the error happened, so that is the part to keep.
    span.put("exception.stacktrace", stack if len(stack) <= _MAX_STACK_CHARS else f"…{stack[-_MAX_STACK_CHARS:]}")
    frame = _app_frame(error)
    if frame is not None:
        span.put("code.location", f"{frame.filename}:{frame.lineno}")
        span.put("code.filepath", os.path.abspath(frame.filename))
        span.put("code.lineno", frame.lineno)
    return span


def _app_frame(error: BaseException) -> traceback.FrameSummary | None:
    """The deepest frame in the application's own code."""
    for frame in reversed(traceback.extract_tb(error.__traceback__)):
        if _is_app_file(frame.filename):
            return frame
    return None


def _is_app_file(filename: str) -> bool:
    if filename.startswith("<") or "site-packages" in filename or "dist-packages" in filename:
        return False
    return not os.path.normcase(os.path.abspath(filename)).startswith((_STDLIB, _THIS_PACKAGE))
