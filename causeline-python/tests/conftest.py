# SPDX-License-Identifier: Apache-2.0
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

import causeline

TOKEN = "test-token"


class FakeCauseline:
    """The peer-spans endpoint of a Spring app's Causeline, over real HTTP."""

    def __init__(self):
        self.spans = []
        self.tokens = []
        self.status = 202
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"]))
                outer.path = self.path
                outer.tokens.append(self.headers.get("X-Causeline-Token"))
                if outer.status == 202:
                    outer.spans.extend(json.loads(body))
                self.send_response(outer.status)
                self.send_header("Content-Length", "0")
                self.end_headers()

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def of_kind(self, kind):
        return [span for span in self.spans if span["kind"] == kind]

    def close(self):
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def receiver():
    fake = FakeCauseline()
    yield fake
    fake.close()


@pytest.fixture
def tracing(receiver):
    """Causeline switched on, sending to the fake receiver."""
    assert causeline.init(endpoint=receiver.url, token=TOKEN, service="ai-service")
    yield receiver
    causeline.shutdown()


@pytest.fixture(autouse=True)
def no_leftover_recorder(monkeypatch):
    for variable in ("ENV", "ENVIRONMENT", "APP_ENV", "PYTHON_ENV", "CAUSELINE_ENDPOINT", "CAUSELINE_TOKEN",
                     "CAUSELINE_SERVICE_NAME"):
        monkeypatch.delenv(variable, raising=False)
    yield
    causeline.shutdown()
