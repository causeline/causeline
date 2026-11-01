# SPDX-License-Identifier: Apache-2.0
"""Request spans, in plain ASGI so FastAPI is never imported."""
from __future__ import annotations

import logging
from typing import Any, Awaitable, Callable, MutableMapping

from . import _runtime
from ._spans import Span, current_span, parse_traceparent

logger = logging.getLogger("causeline")

Scope = MutableMapping[str, Any]
Message = MutableMapping[str, Any]
Receive = Callable[[], Awaitable[Message]]
Send = Callable[[Message], Awaitable[None]]
Asgi = Callable[[Scope, Receive, Send], Awaitable[None]]

# FastAPI's own pages: not something the application does.
_IGNORED_PATHS = frozenset({"/docs", "/docs/oauth2-redirect", "/redoc", "/openapi.json"})


class CauselineMiddleware:
    """Opens a REQUEST span for each HTTP request, continuing the caller's trace."""

    def __init__(self, app: Asgi) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        recorder = _runtime.active()
        span = None
        if recorder is not None and scope["type"] == "http" and scope.get("path") not in _IGNORED_PATHS:
            span = _safely(lambda: _open(recorder, scope))
        if span is None:
            await self.app(scope, receive, send)
            return

        status: list[int] = []

        async def recording_send(message: Message) -> None:
            if message["type"] == "http.response.start":
                status.append(message["status"])
            await send(message)

        token = current_span.set(span)
        failure = None
        try:
            await self.app(scope, receive, recording_send)
        except Exception as error:
            failure = error
            raise
        finally:
            current_span.reset(token)
            _safely(lambda: _close(recorder, span, scope, status, failure))


def instrument(app: Any) -> Any:
    """Adds Causeline to a FastAPI or Starlette app. Harmless if init() is never called."""
    add_middleware = getattr(app, "add_middleware", None)
    if add_middleware is None:
        raise TypeError("instrument() needs a FastAPI or Starlette app; wrap other ASGI apps in CauselineMiddleware")
    add_middleware(CauselineMiddleware)
    return app


def _open(recorder: _runtime.Recorder, scope: Scope) -> Span:
    method = scope.get("method", "GET")
    path = scope.get("path", "")
    context = parse_traceparent(_header(scope, b"traceparent"))
    trace_id, parent_span_id = context if context else (None, None)
    span = recorder.start("REQUEST", f"{method} {path}", trace_id=trace_id, parent_span_id=parent_span_id)
    span.put("http.request.method", method)
    span.put("url.path", path)
    span.put("url.query", scope.get("query_string", b"").decode("latin-1"))
    return span


def _close(recorder: _runtime.Recorder, span: Span, scope: Scope, status: list[int], failure: Exception | None) -> None:
    # The router fills in the route, so it is known only now.
    route = getattr(scope.get("route"), "path", None)
    if route:
        span.put("http.route", route)
        span.name = f"{span.attributes['http.request.method']} {route}"
    code = status[0] if status else (500 if failure is not None else None)
    span.put("http.response.status_code", code)
    if failure is not None:
        recorder.exception(span, failure)
    # Following OpenTelemetry, a 4xx is the caller's mistake, not this server span's failure.
    if failure is not None or (code is not None and code >= 500):
        span.status = "ERROR"
    recorder.end(span)


def _header(scope: Scope, name: bytes) -> str | None:
    for key, value in scope.get("headers", ()):
        if key.lower() == name:
            return value.decode("latin-1")
    return None


def _safely(action: Callable[[], Any]) -> Any:
    """A failure in Causeline loses the span, never the request."""
    try:
        return action()
    except Exception:
        logger.debug("Causeline could not record a span", exc_info=True)
        return None
