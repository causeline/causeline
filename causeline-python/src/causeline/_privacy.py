# SPDX-License-Identifier: Apache-2.0
"""What may leave the service. Python has no SpanRedactor, so every span passes through Outbound."""
from __future__ import annotations

import ipaddress
import re
from urllib.parse import unquote, urlsplit

REDACTED = "[REDACTED]"
WITHHELD = "[withheld on export]"

# Keep these three in step with SensitiveData and AiAttributes.TEXT in causeline-spring-boot.
_BUILT_IN_KEYS = "password|passwd|secret|token|authorization|api[-_]?key|card|cvv|cvc|ssn|iban|pin"
AI_TEXT = frozenset({
    "gen_ai.input.messages",
    "gen_ai.output.messages",
    "gen_ai.system_instructions",
    "causeline.ai.embedding.input",
    "db.vector.query.content",
    "db.vector.query.filter",
    "causeline.ai.documents.content",
})
_KEY_VALUE = re.compile(r"""(["']?)([\w.\-]+)\1(\s*[=:]\s*)("(?:[^"\\]|\\.)*"|'[^']*'|[^\s,;&}\]]+)""")

_BEARER = re.compile(r"(?i)\b(bearer|basic)\s+[\w\-._~+/]+=*")
_ERROR_TEXT = ("exception.message", "exception.stacktrace")


def is_loopback(url: str) -> bool:
    host = (urlsplit(url).hostname or "").lower()
    if host == "localhost" or host.endswith(".localhost"):
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


class Outbound:
    """Turns a span's attributes into what is sent, so no span can skip these rules."""

    def __init__(self, *, local: bool, send_ai_text: bool = False, redact_keys: tuple[str, ...] = ()) -> None:
        self._local = local
        self._send_ai_text = send_ai_text
        extra = "|".join(re.escape(key) for key in redact_keys if key.strip())
        self._sensitive = re.compile(f"{_BUILT_IN_KEYS}|{extra}" if extra else _BUILT_IN_KEYS, re.IGNORECASE)

    def attributes(self, attributes: dict[str, str]) -> dict[str, str]:
        out = dict(attributes)
        if "url.query" in out:
            out["url.query"] = self.redact_query(out["url.query"])
        if self._local:
            return out
        for key in _ERROR_TEXT:
            if key in out:
                out[key] = self.scrub_text(out[key])
        if not self._send_ai_text:
            for key in AI_TEXT & out.keys():
                out[key] = WITHHELD
        return out

    def redact_query(self, query: str) -> str:
        parts = []
        for pair in query.split("&"):
            key, equals, _ = pair.partition("=")
            sensitive = equals and self._sensitive.search(unquote(key))
            parts.append(f"{key}={REDACTED}" if sensitive else pair)
        return "&".join(parts)

    def scrub_text(self, text: str) -> str:
        """Hides sensitive key=value and "key": "value" pairs and bearer or basic credentials."""
        cleaned = _BEARER.sub(rf"\1 {REDACTED}", text)
        out = []
        copied = position = 0
        while position < len(cleaned):
            match = _KEY_VALUE.search(cleaned, position)
            if match is None:
                break
            if self._sensitive.search(match.group(2)):
                out.append(cleaned[copied:match.start(4)])
                out.append(REDACTED)
                copied = position = match.end(4)
            else:
                # A value can itself start with a pair ("user: password=x").
                position = match.end(3)
        out.append(cleaned[copied:])
        return "".join(out)
