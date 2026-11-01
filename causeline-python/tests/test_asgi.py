# SPDX-License-Identifier: Apache-2.0
import asyncio
import socket
import threading
import time

import httpx
import pytest
import uvicorn
from fastapi import APIRouter, FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from fastapi.testclient import TestClient

import causeline
from causeline._asgi import CauselineMiddleware
from causeline._spans import current_span
from conftest import TOKEN

TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
PARENT = "00f067aa0ba902b7"
TRACEPARENT = f"00-{TRACE}-{PARENT}-01"


def build_app():
    app = FastAPI()
    causeline.instrument(app)
    router = APIRouter(prefix="/api")

    @router.get("/items/{item_id}")
    async def item(item_id: str):
        span = current_span.get()
        return {"id": item_id, "span": span and span.span_id}

    @app.get("/sync")
    def sync_route():
        return {"span": current_span.get().span_id}

    @app.get("/thread")
    async def thread_route():
        return {"span": await asyncio.to_thread(lambda: current_span.get().span_id)}

    @app.get("/boom")
    async def boom():
        raise ValueError("no such order")

    @app.get("/teapot")
    async def teapot():
        raise HTTPException(status_code=418)

    @app.get("/unavailable")
    async def unavailable():
        raise HTTPException(status_code=503)

    @app.get("/stream")
    async def stream():
        async def words():
            yield "one "
            await asyncio.sleep(0.05)
            yield "two"

        return StreamingResponse(words())

    @app.get("/wait/{name}")
    async def wait(name: str):
        seen = current_span.get().span_id
        await asyncio.sleep(0.02)
        return {"same": current_span.get().span_id == seen, "span": seen}

    app.include_router(router)
    return app


@pytest.fixture
def client():
    with TestClient(build_app(), raise_server_exceptions=False) as test_client:
        yield test_client


def request_span(receiver):
    causeline.flush()
    spans = receiver.of_kind("REQUEST")
    assert len(spans) == 1
    return spans[0]


def test_records_a_request(tracing, client):
    response = client.get("/api/items/42?page=2")
    span = request_span(tracing)
    assert response.json()["span"] == span["spanId"]
    assert span["name"] == "GET /api/items/{item_id}"
    assert span["source"] == "ai-service"
    assert span["status"] == "OK"
    assert span["parentSpanId"] is None
    assert span["durationNanos"] > 0
    assert span["attributes"] == {
        "http.request.method": "GET",
        "http.route": "/api/items/{item_id}",
        "url.path": "/api/items/42",
        "url.query": "page=2",
        "http.response.status_code": "200",
    }


def test_continues_the_callers_trace(tracing, client):
    client.get("/api/items/1", headers={"traceparent": TRACEPARENT})
    span = request_span(tracing)
    assert span["traceId"] == TRACE
    assert span["parentSpanId"] == PARENT


def test_an_invalid_traceparent_starts_a_new_trace(tracing, client):
    client.get("/api/items/1", headers={"traceparent": "garbage"})
    span = request_span(tracing)
    assert span["traceId"] != TRACE
    assert span["parentSpanId"] is None


def test_nothing_from_the_headers_is_captured(tracing, client):
    client.get("/api/items/1", headers={"Authorization": "Bearer seeded-secret", "Cookie": "s=seeded-secret"})
    assert "seeded-secret" not in str(request_span(tracing))


def test_sensitive_query_values_are_hidden(tracing, client):
    client.get("/api/items/1?token=seeded-secret")
    assert "seeded-secret" not in str(request_span(tracing))


def test_an_unhandled_exception_fails_the_request_span(tracing, client):
    response = client.get("/boom")
    assert response.status_code == 500
    span = request_span(tracing)
    assert span["status"] == "ERROR"
    assert span["attributes"]["http.response.status_code"] == "500"
    exception = tracing.of_kind("EXCEPTION")[0]
    assert exception["parentSpanId"] == span["spanId"]
    assert exception["traceId"] == span["traceId"]
    assert exception["name"] == "ValueError"
    assert exception["attributes"]["exception.message"] == "no such order"
    assert __file__ in exception["attributes"]["code.location"]


def test_a_4xx_is_not_a_server_failure(tracing, client):
    assert client.get("/teapot").status_code == 418
    span = request_span(tracing)
    assert span["status"] == "OK"
    assert span["attributes"]["http.response.status_code"] == "418"


def test_a_5xx_is_a_server_failure_without_an_exception_span(tracing, client):
    assert client.get("/unavailable").status_code == 503
    assert request_span(tracing)["status"] == "ERROR"
    assert tracing.of_kind("EXCEPTION") == []


def test_an_unknown_path_keeps_its_path_as_the_name(tracing, client):
    client.get("/nothing-here")
    span = request_span(tracing)
    assert span["name"] == "GET /nothing-here"
    assert span["attributes"]["http.response.status_code"] == "404"


def test_a_streamed_response_counts_until_it_ends(tracing, client):
    assert client.get("/stream").text == "one two"
    assert request_span(tracing)["durationNanos"] >= 40_000_000


def test_the_span_is_current_on_sync_routes_and_threads(tracing, client):
    first = client.get("/sync").json()["span"]
    second = client.get("/thread").json()["span"]
    causeline.flush()
    assert {first, second} == {span["spanId"] for span in tracing.of_kind("REQUEST")}


def test_the_span_does_not_outlive_its_request(tracing, client):
    client.get("/api/items/1")
    assert current_span.get() is None


@pytest.mark.parametrize("path", ["/docs", "/redoc", "/openapi.json"])
def test_fastapis_own_pages_are_not_traced(tracing, client, path):
    assert client.get(path).status_code == 200
    causeline.flush()
    assert tracing.spans == []


def test_does_nothing_without_init(receiver, client):
    assert client.get("/api/items/1").status_code == 200
    assert causeline.stats() == {"sent": 0, "dropped": 0, "failed": 0}
    assert receiver.spans == []


def test_init_stays_off_in_production(receiver, client, monkeypatch, caplog):
    monkeypatch.setenv("ENV", "production")
    assert causeline.init(endpoint=receiver.url, token=TOKEN) is False
    assert "production" in caplog.text
    client.get("/api/items/1")
    causeline.flush()
    assert receiver.spans == []


def test_requests_succeed_with_causeline_down(client):
    with socket.socket() as unused:
        unused.bind(("127.0.0.1", 0))
        port = unused.getsockname()[1]
    assert causeline.init(endpoint=f"http://127.0.0.1:{port}", token=TOKEN)
    for _ in range(3):
        assert client.get("/api/items/1").status_code == 200
    # Windows retries a refused local connection for about two seconds.
    assert causeline.flush(15)
    assert causeline.stats()["failed"] == 3


def test_a_failure_while_recording_loses_the_span_not_the_request(tracing, client, monkeypatch):
    from causeline import _runtime

    def broken(*args, **kwargs):
        raise RuntimeError("recorder bug")

    monkeypatch.setattr(_runtime.Recorder, "start", broken)
    assert client.get("/api/items/1").status_code == 200
    monkeypatch.undo()
    monkeypatch.setattr(_runtime.Recorder, "end", broken)
    assert client.get("/api/items/1").status_code == 200


def test_an_exception_in_a_broken_recorder_still_reaches_the_app(tracing, client, monkeypatch):
    from causeline import _runtime

    def broken(*args, **kwargs):
        raise RuntimeError("recorder bug")

    monkeypatch.setattr(_runtime.Recorder, "exception", broken)
    assert client.get("/boom").status_code == 500


async def _plain_app(scope, receive, send):
    if scope["type"] == "http":
        await send({"type": "http.response.start", "status": 204, "headers": []})
        await send({"type": "http.response.body", "body": b""})


def test_works_on_any_asgi_app(tracing):
    assert TestClient(CauselineMiddleware(_plain_app)).get("/hello").status_code == 204
    span = request_span(tracing)
    assert span["name"] == "GET /hello"
    assert "http.route" not in span["attributes"]


def test_instrument_refuses_what_it_cannot_add_to():
    with pytest.raises(TypeError, match="CauselineMiddleware"):
        causeline.instrument(_plain_app)


@pytest.fixture
def live_server():
    server = uvicorn.Server(uvicorn.Config(build_app(), host="127.0.0.1", port=0, log_level="warning"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 10
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.01)
    port = server.servers[0].sockets[0].getsockname()[1]
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(5)


def test_a_real_server_keeps_concurrent_requests_apart(tracing, live_server):
    async def call_all():
        async with httpx.AsyncClient(base_url=live_server) as http:
            return await asyncio.gather(*(
                http.get(f"/wait/{index}", headers={"traceparent": f"00-{index + 1:032x}-{PARENT}-01"})
                for index in range(20)
            ))

    responses = asyncio.run(call_all())
    assert all(response.json()["same"] for response in responses)
    causeline.flush()
    spans = {span["attributes"]["url.path"]: span for span in tracing.of_kind("REQUEST")}
    assert len(spans) == 20
    for index in range(20):
        assert spans[f"/wait/{index}"]["traceId"] == f"{index + 1:032x}"
    assert {response.json()["span"] for response in responses} == {span["spanId"] for span in spans.values()}
