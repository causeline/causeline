# SPDX-License-Identifier: Apache-2.0
"""Settings, and the one recorder the process uses once init() has been called."""
from __future__ import annotations

import atexit
import logging
import os
import re
from dataclasses import dataclass
from typing import Iterable, Mapping
from urllib.parse import urlsplit

from ._privacy import Outbound, is_loopback
from ._sender import Sender, http_post
from ._spans import Span, exception_span, new_span_id, new_trace_id

logger = logging.getLogger("causeline")

CAPTURE_SWITCHES = frozenset({"prompts", "completions", "documents"})
_ENVIRONMENT_VARIABLES = ("ENV", "ENVIRONMENT", "APP_ENV", "PYTHON_ENV")
_PRODUCTION = re.compile(r"prod.*", re.IGNORECASE)


@dataclass(frozen=True)
class Settings:
    url: str
    token: str
    service: str
    local: bool
    capture: frozenset[str]
    send_ai_text: bool
    redact_keys: tuple[str, ...]


class Recorder:
    def __init__(self, settings: Settings, sender: Sender) -> None:
        self.settings = settings
        self.sender = sender

    def start(self, kind: str, name: str, *, trace_id: str | None = None, parent_span_id: str | None = None) -> Span:
        return Span(trace_id or new_trace_id(), new_span_id(), parent_span_id, kind, name, self.settings.service)

    def end(self, span: Span) -> None:
        span.finish()
        self.sender.submit(span)

    def exception(self, span: Span, error: BaseException) -> None:
        self.sender.submit(exception_span(span, error))


def resolve_settings(
    *,
    endpoint: str | None = None,
    token: str | None = None,
    service: str | None = None,
    capture: Iterable[str] | None = None,
    send_ai_text: bool = False,
    redact_keys: Iterable[str] = (),
    environ: Mapping[str, str] | None = None,
) -> Settings | str:
    """Settings with defaults applied, or the reason Causeline stays off."""
    env = os.environ if environ is None else environ
    for variable in _ENVIRONMENT_VARIABLES:
        if _PRODUCTION.fullmatch(env.get(variable, "").strip()):
            return f"{variable} says this is production"
    endpoint = endpoint or env.get("CAUSELINE_ENDPOINT")
    if not endpoint:
        return "no endpoint: pass endpoint= or set CAUSELINE_ENDPOINT"
    token = token or env.get("CAUSELINE_TOKEN")
    if not token:
        return "no access token: pass token= or set CAUSELINE_TOKEN to your app's causeline.access-token"
    parts = urlsplit(endpoint)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        return "the endpoint is not an http or https URL"
    chosen = CAPTURE_SWITCHES if capture is None else frozenset(capture)
    unknown = chosen - CAPTURE_SWITCHES
    if unknown:
        # Carrying on would capture what the developer meant to switch off.
        return f"unknown capture switch: {', '.join(sorted(unknown))}"
    base = endpoint.rstrip("/")
    if not base.endswith("/causeline"):
        base = f"{base}/causeline"
    return Settings(
        url=f"{base}/api/peer-spans",
        token=token,
        service=service or env.get("CAUSELINE_SERVICE_NAME") or "python",
        local=is_loopback(base),
        capture=chosen,
        send_ai_text=send_ai_text,
        redact_keys=tuple(redact_keys),
    )


_recorder: Recorder | None = None


def active() -> Recorder | None:
    return _recorder


def init(
    *,
    endpoint: str | None = None,
    token: str | None = None,
    service: str | None = None,
    capture: Iterable[str] | None = None,
    send_ai_text: bool = False,
    redact_keys: Iterable[str] = (),
) -> bool:
    """
    Starts sending spans to the Causeline in your Spring Boot app. Returns whether it did.

    Stays off, with a warning, without an endpoint and token or in a production environment. A
    service that never calls this runs none of Causeline's code on its requests.

    :param endpoint: base URL of the Spring app. Default: ``CAUSELINE_ENDPOINT``.
    :param token: that app's ``causeline.access-token``. Default: ``CAUSELINE_TOKEN``.
    :param service: name shown for these spans. Default: ``CAUSELINE_SERVICE_NAME``, else ``python``.
    :param capture: which AI text to capture, of ``prompts``, ``completions`` and ``documents``.
        Default: all of them.
    :param send_ai_text: also send AI text to an endpoint on another host. It is always sent to
        one on this machine.
    :param redact_keys: more query parameters whose values are hidden.
    """
    global _recorder
    shutdown()
    settings = resolve_settings(endpoint=endpoint, token=token, service=service, capture=capture,
                                send_ai_text=send_ai_text, redact_keys=redact_keys)
    if isinstance(settings, str):
        logger.warning("Causeline is off: %s", settings)
        return False
    outbound = Outbound(local=settings.local, send_ai_text=settings.send_ai_text, redact_keys=settings.redact_keys)
    sender = Sender(http_post(settings.url, settings.token, local=settings.local), outbound)
    _recorder = Recorder(settings, sender)
    atexit.register(shutdown)
    return True


def flush(timeout: float = 2.0) -> bool:
    """Waits for spans recorded so far to be sent. False if they weren't within the timeout."""
    return _recorder is None or _recorder.sender.flush(timeout)


def shutdown() -> None:
    """Sends what is queued and stops. Called at exit; tests and reloaders can call it too."""
    global _recorder
    recorder, _recorder = _recorder, None
    if recorder is not None:
        atexit.unregister(shutdown)
        recorder.sender.stop()


def stats() -> dict[str, int]:
    """Spans sent, dropped because the queue was full, and lost to a failed upload."""
    if _recorder is None:
        return {"sent": 0, "dropped": 0, "failed": 0}
    sender = _recorder.sender
    return {"sent": sender.sent, "dropped": sender.dropped, "failed": sender.failed}
