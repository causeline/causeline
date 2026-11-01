# SPDX-License-Identifier: Apache-2.0
import hashlib
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ANSWER = "Refunds take five days"
USAGE = {"prompt_tokens": 11, "completion_tokens": 4, "total_tokens": 15,
         "prompt_tokens_details": {"cached_tokens": 2}}


def embed(text):
    """Texts that share words get similar vectors, so searches find what they should."""
    vector = [0.0] * 32
    for word in str(text).lower().split():
        vector[hashlib.sha256(word.encode()).digest()[0] % 32] += 1.0
    return vector if any(vector) else [1.0] + [0.0] * 31


class FakeModel:
    """The parts of the OpenAI HTTP API the real client calls, so the real client runs."""

    def __init__(self):
        outer = self
        self.embedding_calls = 0

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if self.path.endswith("/embeddings"):
                    outer.embedding_calls += 1
                    self.embeddings(body)
                elif "boom" in json.dumps(body["messages"]):
                    self.send_response(500)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                elif body.get("stream"):
                    self.stream(body)
                else:
                    self.complete(body)

            def embeddings(self, body):
                inputs = body["input"] if isinstance(body["input"], list) else [body["input"]]
                self.json({"object": "list", "model": body["model"], "usage": {"prompt_tokens": 3, "total_tokens": 3},
                           "data": [{"object": "embedding", "index": index, "embedding": embed(text)}
                                    for index, text in enumerate(inputs)]})

            def complete(self, body):
                cut = body.get("max_completion_tokens") or body.get("max_tokens")
                self.json({"id": "c", "object": "chat.completion", "created": 1, "model": body["model"], "usage": USAGE,
                           "choices": [{"index": 0, "message": {"role": "assistant", "content": ANSWER},
                                        "finish_reason": "length" if cut else "stop"}]})

            def stream(self, body):
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                chunk = {"id": "c", "object": "chat.completion.chunk", "model": body["model"]}
                for word in ANSWER.split(" "):
                    self.event({**chunk, "choices": [{"index": 0, "delta": {"content": f"{word} "}, "finish_reason": None}]})
                self.event({**chunk, "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]})
                self.event({**chunk, "choices": [], "usage": USAGE})
                self.wfile.write(b"data: [DONE]\n\n")

            def event(self, value):
                self.wfile.write(b"data: " + json.dumps(value).encode() + b"\n\n")
                self.wfile.flush()

            def json(self, value):
                body = json.dumps(value).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}/v1"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
