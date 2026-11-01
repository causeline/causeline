# SPDX-License-Identifier: Apache-2.0
"""Sends finished spans from a daemon thread. Nothing here runs on a request thread except submit()."""
from __future__ import annotations

import json
import logging
import queue
import threading
import time
import urllib.error
import urllib.request
from typing import Callable

from ._privacy import Outbound
from ._spans import Span

logger = logging.getLogger("causeline")

_CAPACITY = 2_048
# Spring accepts up to 2,000 spans per request; stay well below.
_MAX_PER_REQUEST = 500
# Each model call can hold 130 KB of text, so span counts alone would allow hundreds of megabytes.
_MAX_QUEUED_CHARS = 64_000_000
_MAX_CHARS_PER_REQUEST = 8_000_000
_DELAY_SECONDS = 0.5
_TIMEOUT_SECONDS = 5.0

Post = Callable[[bytes], None]


class Sender:
    def __init__(self, post: Post, outbound: Outbound, *, capacity: int = _CAPACITY, delay: float = _DELAY_SECONDS,
                 max_queued_chars: int = _MAX_QUEUED_CHARS) -> None:
        self._post = post
        self._outbound = outbound
        self._delay = delay
        self._max_queued_chars = max_queued_chars
        self._queued_chars = 0
        self._queue: queue.Queue[Span | None] = queue.Queue(capacity)
        self._hurry = threading.Event()
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._warned = False
        self.sent = 0
        self.dropped = 0
        self.failed = 0

    def submit(self, span: Span) -> None:
        self._ensure_thread()
        size = span.size()
        with self._lock:
            if self._queued_chars + size > self._max_queued_chars:
                self.dropped += 1
                return
            self._queued_chars += size
        try:
            self._queue.put_nowait(span)
        except queue.Full:
            with self._lock:
                self._queued_chars -= size
                self.dropped += 1

    def flush(self, timeout: float = 2.0) -> bool:
        """Waits for queued spans to be sent. False if they weren't within the timeout."""
        self._hurry.set()
        deadline = time.monotonic() + timeout
        try:
            while self._queue.unfinished_tasks:
                if time.monotonic() >= deadline:
                    return False
                time.sleep(0.01)
            return True
        finally:
            self._hurry.clear()

    def stop(self, timeout: float = 2.0) -> None:
        self.flush(timeout)
        thread = self._thread
        if thread is not None and thread.is_alive():
            try:
                self._queue.put_nowait(None)
            except queue.Full:
                pass  # It is a daemon thread, so it can't hold up the process.

    def _ensure_thread(self) -> None:
        # is_alive also turns false in a forked worker, where the parent's thread doesn't exist.
        if self._thread is None or not self._thread.is_alive():
            with self._lock:
                if self._thread is None or not self._thread.is_alive():
                    self._thread = threading.Thread(target=self._run, name="causeline-sender", daemon=True)
                    self._thread.start()

    def _run(self) -> None:
        while True:
            first = self._queue.get()
            batch = [first]
            if first is not None:
                self._collect(batch)
            spans = [span for span in batch if span is not None]
            try:
                if spans:
                    self._send(spans)
            finally:
                with self._lock:
                    self._queued_chars -= sum(span.size() for span in spans)
                for _ in batch:
                    self._queue.task_done()
            if len(spans) < len(batch):
                return

    def _collect(self, batch: list[Span | None]) -> None:
        deadline = time.monotonic() + self._delay
        chars = batch[0].size()
        while len(batch) < _MAX_PER_REQUEST and chars < _MAX_CHARS_PER_REQUEST and batch[-1] is not None:
            wait = 0.0 if self._hurry.is_set() else deadline - time.monotonic()
            try:
                # Short waits, so a flush doesn't sit out the whole delay.
                span = self._queue.get(timeout=min(wait, 0.05)) if wait > 0 else self._queue.get_nowait()
                batch.append(span)
                chars += span.size() if span is not None else 0
            except queue.Empty:
                if wait <= 0:
                    return

    def _send(self, spans: list[Span]) -> None:
        try:
            body = json.dumps([span.to_json(self._outbound.attributes(span.attributes)) for span in spans],
                              ensure_ascii=False).encode("utf-8")
            self._post(body)
        except Exception as error:  # The sender must survive anything, or tracing stops silently.
            with self._lock:
                self.failed += len(spans)
            self._warn_once(error)
        else:
            with self._lock:
                self.sent += len(spans)

    def _warn_once(self, error: Exception) -> None:
        if self._warned:
            return
        self._warned = True
        if isinstance(error, urllib.error.HTTPError) and error.code == 401:
            reason = "the access token was refused; check it matches causeline.access-token"
        else:
            reason = str(error)
        logger.warning("could not send spans to Causeline: %s", reason)


def http_post(url: str, token: str, *, local: bool) -> Post:
    # A proxy set for the internet must not see a local upload, or the token.
    handlers = [urllib.request.ProxyHandler({})] if local else []
    opener = urllib.request.build_opener(*handlers)

    def post(body: bytes) -> None:
        request = urllib.request.Request(url, data=body, method="POST", headers={
            "Content-Type": "application/json",
            "X-Causeline-Token": token,
        })
        with opener.open(request, timeout=_TIMEOUT_SECONDS):
            pass

    return post
