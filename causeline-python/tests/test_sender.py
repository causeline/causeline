# SPDX-License-Identifier: Apache-2.0
import json
import logging
import subprocess
import sys
import threading
from pathlib import Path

from causeline import _sender as sender_module
from causeline._privacy import WITHHELD, Outbound
from causeline._sender import Sender, http_post
from causeline._spans import Span, new_span_id, new_trace_id

LOCAL = Outbound(local=True)


def span(**attributes):
    return Span(new_trace_id(), new_span_id(), None, "REQUEST", "GET /", "ai-service", attributes=attributes)


class Recording:
    def __init__(self):
        self.bodies = []

    def __call__(self, body):
        self.bodies.append(json.loads(body))

    @property
    def spans(self):
        return [item for body in self.bodies for item in body]


def test_sends_what_it_is_given():
    post = Recording()
    sender = Sender(post, LOCAL)
    sender.submit(span())
    assert sender.flush()
    assert len(post.spans) == 1
    assert sender.sent == 1
    sender.stop()


def test_splits_large_batches():
    post = Recording()
    sender = Sender(post, LOCAL, delay=0.2)
    for _ in range(1_200):
        sender.submit(span())
    assert sender.flush(5)
    assert len(post.spans) == 1_200
    assert max(len(body) for body in post.bodies) <= 500
    sender.stop()


def test_every_span_goes_through_the_outbound_rules():
    post = Recording()
    sender = Sender(post, Outbound(local=False))
    sender.submit(span(**{"gen_ai.input.messages": "seeded"}))
    sender.flush()
    assert post.spans[0]["attributes"]["gen_ai.input.messages"] == WITHHELD
    sender.stop()


def test_a_full_queue_drops_and_counts_without_waiting():
    release = threading.Event()
    sender = Sender(lambda body: release.wait(5), LOCAL, capacity=3, delay=0)
    for _ in range(50):
        sender.submit(span())
    assert sender.dropped > 0
    release.set()
    sender.stop()


def test_large_spans_are_dropped_before_they_fill_memory():
    release = threading.Event()
    sender = Sender(lambda body: release.wait(5), LOCAL, delay=0, max_queued_chars=1_000)
    for _ in range(5):
        sender.submit(span(text="x" * 400))
    assert sender.dropped >= 2
    release.set()
    sender.stop()


def test_queued_size_is_given_back_once_sent():
    post = Recording()
    sender = Sender(post, LOCAL, max_queued_chars=1_000)
    for _ in range(5):
        sender.submit(span(text="x" * 400))
        assert sender.flush()
    assert sender.dropped == 0
    assert len(post.spans) == 5
    sender.stop()


def test_large_spans_go_in_several_requests(monkeypatch):
    monkeypatch.setattr(sender_module, "_MAX_CHARS_PER_REQUEST", 1_000)
    post = Recording()
    sender = Sender(post, LOCAL)
    for _ in range(6):
        sender.submit(span(text="x" * 400))
    assert sender.flush(5)
    assert len(post.spans) == 6
    assert max(len(body) for body in post.bodies) <= 3
    sender.stop()


def test_a_failed_upload_is_counted_and_does_not_stop_the_sender(caplog):
    outcomes = iter([OSError("connection refused")])
    post = Recording()

    def flaky(body):
        for outcome in outcomes:
            raise outcome
        post(body)

    sender = Sender(flaky, LOCAL)
    with caplog.at_level(logging.WARNING, "causeline"):
        sender.submit(span())
        sender.flush()
        sender.submit(span())
        sender.flush()
    assert sender.failed == 1
    assert sender.sent == 1
    assert "connection refused" in caplog.text
    sender.stop()


def test_warns_about_a_failing_endpoint_once(caplog):
    def down(body):
        raise OSError("down")

    sender = Sender(down, LOCAL)
    with caplog.at_level(logging.WARNING, "causeline"):
        for _ in range(3):
            sender.submit(span())
            sender.flush()
    assert caplog.text.count("down") == 1
    sender.stop()


def test_posts_json_with_the_token_over_http(receiver):
    post = http_post(f"{receiver.url}/causeline/api/peer-spans", "the-token", local=True)
    sender = Sender(post, LOCAL)
    sender.submit(span(note="héllo"))
    sender.flush()
    assert receiver.path == "/causeline/api/peer-spans"
    assert receiver.tokens == ["the-token"]
    assert receiver.spans[0]["attributes"]["note"] == "héllo"
    sender.stop()


def test_a_refused_token_says_so(receiver, caplog):
    receiver.status = 401
    sender = Sender(http_post(receiver.url, "wrong", local=True), LOCAL)
    with caplog.at_level(logging.WARNING, "causeline"):
        sender.submit(span())
        sender.flush()
    assert "access token was refused" in caplog.text
    assert "wrong" not in caplog.text
    assert sender.failed == 1
    sender.stop()


def test_a_local_upload_ignores_proxy_settings(receiver, monkeypatch):
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:9")
    monkeypatch.setenv("http_proxy", "http://127.0.0.1:9")
    sender = Sender(http_post(receiver.url, "t", local=True), LOCAL)
    sender.submit(span())
    sender.flush()
    assert sender.sent == 1
    sender.stop()


def test_queued_spans_are_sent_when_the_process_exits(receiver):
    script = f"""
import causeline
from causeline import _runtime
causeline.init(endpoint={receiver.url!r}, token="t")
recorder = _runtime.active()
recorder.end(recorder.start("REQUEST", "GET /last"))
"""
    subprocess.run([sys.executable, "-c", script], check=True, timeout=30)
    assert [item["name"] for item in receiver.spans] == ["GET /last"]


def test_importing_causeline_loads_nothing_else():
    script = """
import sys
import causeline
print([name for name in ("fastapi", "starlette", "langchain_core", "opentelemetry") if name in sys.modules])
"""
    output = subprocess.run([sys.executable, "-c", script], check=True, capture_output=True, text=True, timeout=30)
    assert output.stdout.strip() == "[]"


def test_declares_no_dependencies():
    text = (Path(__file__).resolve().parents[1] / "pyproject.toml").read_text(encoding="utf-8")
    assert "dependencies = []" in text
