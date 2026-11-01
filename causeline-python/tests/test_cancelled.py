# SPDX-License-Identifier: Apache-2.0
import asyncio

import pytest
from langchain_openai import ChatOpenAI

import causeline
from causeline import _ai
from causeline import langchain as traced
from fake_model import FakeModel


@pytest.fixture
def model():
    fake = FakeModel()
    yield fake
    fake.close()


def stream_llm(model):
    return ChatOpenAI(model="fake-chat", api_key="sk-test", base_url=model.url, streaming=True)


def test_an_abandoned_async_stream_keeps_its_prompt(tracing, model):
    async def abandon():
        stream = stream_llm(model).astream("When?")
        await stream.__anext__()
        await stream.aclose()

    asyncio.run(abandon())
    causeline.flush()
    call = tracing.of_kind("AI_MODEL")[0]
    assert "When?" in call["attributes"][_ai.INPUT_MESSAGES]
    assert _ai.OUTPUT_MESSAGES not in call["attributes"]
    assert call["status"] == "OK"
    assert tracing.of_kind("EXCEPTION") == []
    assert traced._handler._runs == {}


def test_an_abandoned_sync_stream_keeps_its_prompt(tracing, model):
    stream = stream_llm(model).stream("When?")
    next(stream)
    stream.close()
    causeline.flush()
    call = tracing.of_kind("AI_MODEL")[0]
    assert "When?" in call["attributes"][_ai.INPUT_MESSAGES]
    assert call["status"] == "OK"
    assert tracing.of_kind("EXCEPTION") == []
    assert traced._handler._runs == {}
