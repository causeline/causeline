# SPDX-License-Identifier: Apache-2.0
import asyncio
import json
import threading
import time

import httpx
import pytest
import uvicorn
from fastapi import FastAPI
from fastapi.testclient import TestClient
from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_core.language_models.fake import FakeListLLM
from langchain_core.messages import HumanMessage
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.retrievers import BaseRetriever
from langchain_core.runnables import RunnableLambda, RunnablePassthrough
from langchain_core.vectorstores import InMemoryVectorStore
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

import causeline
from causeline import _ai, _runtime
from causeline import langchain as traced
from causeline._privacy import WITHHELD
from causeline.langchain import scored_retriever, traced_embeddings
from conftest import TOKEN
from fake_model import ANSWER, FakeModel

SECRET = "seeded-chunk-secret"
API_KEY = "sk-test-key-1234567890"
REFUNDS = "Refunds are issued within five days"
SHIPPING = "Shipping takes a whole week"


@pytest.fixture
def model():
    fake = FakeModel()
    yield fake
    fake.close()


def embeddings(model):
    return OpenAIEmbeddings(model="fake-embed", api_key=API_KEY, base_url=model.url, check_embedding_ctx_length=False)


def chat(model, **options):
    return ChatOpenAI(model="fake-chat", api_key=API_KEY, base_url=model.url, **options)


def documents():
    return [Document(id="refunds", page_content=REFUNDS, metadata={"source": "policy.md"}),
            Document(id="shipping", page_content=SHIPPING, metadata={"source": "ship.md"})]


def filled_store(model, embedder=None):
    store = InMemoryVectorStore(embedder or embeddings(model))
    store.add_documents(documents())
    return store


def rag_chain(retriever, llm):
    prompt = ChatPromptTemplate.from_messages([("system", "Use this: {context}"), ("human", "{question}")])
    context = retriever | RunnableLambda(lambda found: "\n".join(d.page_content for d in found))
    return {"context": context, "question": RunnablePassthrough()} | prompt | llm | StrOutputParser()


def app_with(chain):
    app = FastAPI()
    causeline.instrument(app)

    @app.post("/ask")
    async def ask(question: str):
        return {"answer": await chain.ainvoke(question)}

    @app.post("/ask-sync")
    def ask_sync(question: str):
        return {"answer": chain.invoke(question)}

    return app


def spans_of(receiver, kind):
    causeline.flush()
    return receiver.of_kind(kind)


def only(receiver, kind):
    found = spans_of(receiver, kind)
    assert len(found) == 1
    return found[0]


def parsed(span, key):
    return json.loads(span["attributes"][key])


def test_a_rag_chain_needs_no_changes_and_nests_under_its_request(tracing, model):
    chain = rag_chain(filled_store(model).as_retriever(), chat(model))
    with TestClient(app_with(chain)) as client:
        assert client.post("/ask", params={"question": "When are refunds issued?"}).json() == {"answer": ANSWER}
    request = only(tracing, "REQUEST")
    search = only(tracing, "AI_RETRIEVAL")
    call = only(tracing, "AI_MODEL")
    assert {span["traceId"] for span in tracing.spans} == {request["traceId"]}
    assert search["parentSpanId"] == request["spanId"]
    assert call["parentSpanId"] == request["spanId"]
    assert search["source"] == "ai-service"


@pytest.mark.parametrize("route", ["/ask", "/ask-sync"])
def test_a_search_ends_before_the_chat_call_starts(tracing, model, route):
    # rag.ts and AiChecks pair them by this order.
    chain = rag_chain(filled_store(model).as_retriever(), chat(model))
    with TestClient(app_with(chain)) as client:
        client.post(route, params={"question": "When?"})
    search = only(tracing, "AI_RETRIEVAL")
    call = only(tracing, "AI_MODEL")
    assert search["startTimeUnixNano"] + search["durationNanos"] <= call["startTimeUnixNano"]


def test_a_chat_call_records_what_the_rag_panel_reads(tracing, model):
    chat(model, temperature=0.2).invoke([("system", "Be brief"), ("human", "When?")])
    call = only(tracing, "AI_MODEL")
    attributes = call["attributes"]
    assert call["name"] == "chat fake-chat"
    assert attributes["gen_ai.operation.name"] == "chat"
    assert attributes["gen_ai.system"] == "openai"
    assert attributes["gen_ai.request.model"] == "fake-chat"
    assert attributes["gen_ai.response.model"] == "fake-chat"
    assert attributes["gen_ai.request.temperature"] == "0.2"
    assert attributes["gen_ai.usage.input_tokens"] == "11"
    assert attributes["gen_ai.usage.output_tokens"] == "4"
    assert attributes["gen_ai.usage.total_tokens"] == "15"
    assert attributes["gen_ai.usage.cache_read.input_tokens"] == "2"
    assert attributes["gen_ai.response.finish_reasons"] == '["stop"]'
    assert parsed(call, _ai.INPUT_MESSAGES) == [{"role": "user", "parts": [{"type": "text", "content": "When?"}]}]
    assert parsed(call, _ai.SYSTEM_INSTRUCTIONS) == [{"type": "text", "content": "Be brief"}]
    assert parsed(call, _ai.OUTPUT_MESSAGES)[0]["parts"][0]["content"] == ANSWER
    assert attributes[_ai.INPUT_CHARS] == str(len("Be brief") + len("When?"))
    assert attributes[_ai.OUTPUT_CHARS] == str(len(ANSWER))


def test_the_output_limit_and_a_cut_off_answer_are_recorded(tracing, model):
    chat(model, max_completion_tokens=5).invoke("When?")
    call = only(tracing, "AI_MODEL")
    assert call["attributes"]["gen_ai.request.max_tokens"] == "5"
    assert call["attributes"]["gen_ai.response.finish_reasons"] == '["length"]'
    assert parsed(call, _ai.OUTPUT_MESSAGES)[0]["finish_reason"] == "length"


@pytest.mark.parametrize("use_async", [False, True])
def test_a_streamed_answer_is_recorded_whole(tracing, model, use_async):
    llm = chat(model, streaming=True, stream_usage=True)

    async def consume():
        return [chunk async for chunk in llm.astream("When?")]

    list(llm.stream("When?")) if not use_async else asyncio.run(consume())
    call = only(tracing, "AI_MODEL")
    assert parsed(call, _ai.OUTPUT_MESSAGES)[0]["parts"][0]["content"].strip() == ANSWER
    assert call["attributes"]["gen_ai.usage.output_tokens"] == "4"
    assert call["attributes"]["gen_ai.response.finish_reasons"] == '["stop"]'


def test_multimodal_content_keeps_its_text(tracing, model):
    message = HumanMessage(content=[{"type": "text", "text": "What is this?"},
                                    {"type": "image_url", "image_url": {"url": "data:image/png;base64,AAAA"}}])
    chat(model).invoke([message])
    call = only(tracing, "AI_MODEL")
    assert parsed(call, _ai.INPUT_MESSAGES)[0]["parts"][0]["content"] == "What is this?"
    assert "AAAA" not in str(call)


def test_a_text_model_is_recorded_too(tracing):
    FakeListLLM(responses=["Soon"]).invoke("When?")
    call = only(tracing, "AI_MODEL")
    assert parsed(call, _ai.INPUT_MESSAGES)[0]["parts"][0]["content"] == "When?"
    assert parsed(call, _ai.OUTPUT_MESSAGES)[0]["parts"][0]["content"] == "Soon"


def test_a_failed_model_call_keeps_its_prompt(tracing, model):
    with pytest.raises(Exception):
        chat(model, max_retries=0).invoke("boom")
    call = only(tracing, "AI_MODEL")
    assert call["status"] == "ERROR"
    assert "boom" in call["attributes"][_ai.INPUT_MESSAGES]
    exception = only(tracing, "EXCEPTION")
    assert exception["parentSpanId"] == call["spanId"]


def test_a_search_records_its_query_and_documents(tracing, model):
    filled_store(model).as_retriever().invoke("When are refunds issued?")
    search = only(tracing, "AI_RETRIEVAL")
    assert search["name"] == "InMemoryVectorStore query"
    assert search["attributes"]["db.system"] == "InMemoryVectorStore"
    assert search["attributes"]["db.operation.name"] == "query"
    assert search["attributes"][_ai.SEARCH_QUERY] == "When are refunds issued?"
    assert search["attributes"][_ai.DOCUMENT_COUNT] == "2"
    assert [doc["id"] for doc in parsed(search, _ai.DOCUMENTS)][0] == "refunds"
    assert parsed(search, _ai.DOCUMENT_CONTENT)[0] == {"text": REFUNDS, "source": "policy.md"}


def test_a_plain_retriever_gives_no_scores(tracing, model):
    filled_store(model).as_retriever().invoke("When?")
    assert all("score" not in doc for doc in parsed(only(tracing, "AI_RETRIEVAL"), _ai.DOCUMENTS))


class Canned(BaseRetriever):
    found: list = []
    failure: bool = False

    def _get_relevant_documents(self, query, *, run_manager):
        if self.failure:
            raise RuntimeError("store is down")
        return self.found


@pytest.mark.parametrize("key", ["score", "relevance_score"])
def test_a_score_in_the_metadata_is_used(tracing, key):
    Canned(found=[Document(page_content="text", metadata={key: 0.75})]).invoke("When?")
    assert parsed(only(tracing, "AI_RETRIEVAL"), _ai.DOCUMENTS)[0]["score"] == 0.75


@pytest.mark.parametrize("value", [True, "high", float("nan"), float("inf"), None])
def test_something_that_is_not_a_score_is_ignored(tracing, value):
    Canned(found=[Document(page_content="text", metadata={"score": value})]).invoke("When?")
    assert "score" not in parsed(only(tracing, "AI_RETRIEVAL"), _ai.DOCUMENTS)[0]


def test_a_document_without_an_id_gets_one_that_survives_between_runs(tracing):
    found = [Document(page_content="same text")]
    Canned(found=found).invoke("one")
    Canned(found=found).invoke("two")
    first, second = spans_of(tracing, "AI_RETRIEVAL")
    assert parsed(first, _ai.DOCUMENTS)[0]["id"] == parsed(second, _ai.DOCUMENTS)[0]["id"] != ""


def test_an_empty_search_records_a_count_of_zero(tracing):
    # The Empty retrieval hint reads this.
    Canned(found=[]).invoke("When?")
    assert only(tracing, "AI_RETRIEVAL")["attributes"][_ai.DOCUMENT_COUNT] == "0"


def test_a_failed_search_is_recorded(tracing):
    with pytest.raises(RuntimeError):
        Canned(failure=True).invoke("When?")
    assert only(tracing, "AI_RETRIEVAL")["status"] == "ERROR"
    assert only(tracing, "EXCEPTION")["attributes"]["exception.message"] == "store is down"


def test_a_model_call_inside_a_retriever_nests_under_it(tracing, model):
    llm = chat(model)

    class Rewriting(BaseRetriever):
        def _get_relevant_documents(self, query, *, run_manager):
            llm.invoke(query, config={"callbacks": run_manager.get_child()})
            return []

    Rewriting().invoke("When?")
    assert only(tracing, "AI_MODEL")["parentSpanId"] == only(tracing, "AI_RETRIEVAL")["spanId"]


def test_without_a_request_one_run_is_one_trace(tracing, model):
    rag_chain(filled_store(model).as_retriever(), chat(model)).invoke("When?")
    search = only(tracing, "AI_RETRIEVAL")
    call = only(tracing, "AI_MODEL")
    assert search["traceId"] == call["traceId"]
    assert search["parentSpanId"] is None


def test_separate_runs_without_a_request_are_separate_traces(tracing, model):
    chat(model).invoke("one")
    chat(model).invoke("two")
    first, second = spans_of(tracing, "AI_MODEL")
    assert first["traceId"] != second["traceId"]


def test_the_api_key_is_never_sent(tracing, model):
    rag_chain(filled_store(model).as_retriever(), chat(model)).invoke("When?")
    causeline.flush()
    assert tracing.spans
    assert API_KEY not in json.dumps(tracing.spans)


@pytest.mark.parametrize("switch,absent,present", [
    ("prompts", {_ai.INPUT_MESSAGES, _ai.SYSTEM_INSTRUCTIONS, _ai.INPUT_CHARS}, {_ai.OUTPUT_MESSAGES}),
    ("completions", {_ai.OUTPUT_MESSAGES, _ai.OUTPUT_CHARS}, {_ai.INPUT_MESSAGES}),
])
def test_a_switched_off_part_of_a_call_is_not_captured(receiver, model, switch, absent, present):
    chosen = {"prompts", "completions", "documents"} - {switch}
    assert causeline.init(endpoint=receiver.url, token=TOKEN, capture=chosen)
    chat(model).invoke([("system", SECRET), ("human", SECRET)])
    attributes = only(receiver, "AI_MODEL")["attributes"]
    assert not absent & attributes.keys()
    assert present <= attributes.keys()
    assert "gen_ai.usage.input_tokens" in attributes


def test_switching_off_prompts_keeps_seeded_text_out(receiver, model):
    assert causeline.init(endpoint=receiver.url, token=TOKEN, capture={"completions", "documents"})
    chat(model).invoke(SECRET)
    assert SECRET not in str(only(receiver, "AI_MODEL"))


def test_switching_off_documents_keeps_metadata_only(receiver):
    assert causeline.init(endpoint=receiver.url, token=TOKEN, capture={"prompts", "completions"})
    Canned(found=[Document(id="a", page_content=SECRET, metadata={"score": 0.9})]).invoke(SECRET)
    search = only(receiver, "AI_RETRIEVAL")
    assert SECRET not in str(search)
    assert parsed(search, _ai.DOCUMENTS)[0]["score"] == 0.9


def test_a_seeded_chunk_reaches_a_local_causeline(tracing):
    Canned(found=[Document(page_content=SECRET)]).invoke("When?")
    assert SECRET in str(only(tracing, "AI_RETRIEVAL"))


def remote(receiver, monkeypatch, **options):
    """A receiver treated as another host, so the rules for one apply."""
    monkeypatch.setattr(_runtime, "is_loopback", lambda url: False)
    assert causeline.init(endpoint=receiver.url, token=TOKEN, **options)


def test_a_seeded_chunk_never_reaches_another_host(receiver, monkeypatch):
    remote(receiver, monkeypatch)
    Canned(found=[Document(id="a", page_content=SECRET, metadata={"score": 0.9})]).invoke(SECRET)
    FakeListLLM(responses=[SECRET]).invoke(SECRET)
    causeline.flush()
    assert receiver.spans
    assert SECRET not in json.dumps(receiver.spans)
    search = only(receiver, "AI_RETRIEVAL")
    assert search["attributes"][_ai.DOCUMENT_CONTENT] == WITHHELD
    assert search["attributes"][_ai.SEARCH_QUERY] == WITHHELD
    assert parsed(search, _ai.DOCUMENTS)[0]["score"] == 0.9
    assert only(receiver, "AI_MODEL")["attributes"][_ai.INPUT_MESSAGES] == WITHHELD


def test_send_ai_text_allows_another_host(receiver, monkeypatch):
    remote(receiver, monkeypatch, send_ai_text=True)
    Canned(found=[Document(page_content=SECRET)]).invoke("When?")
    assert SECRET in str(only(receiver, "AI_RETRIEVAL"))


def test_hint_limits_travel_on_the_spans(receiver):
    assert causeline.init(endpoint=receiver.url, token=TOKEN, low_score=0.7, context_limit=8_000)
    Canned(found=[]).invoke("When?")
    FakeListLLM(responses=["Soon"]).invoke("When?")
    assert only(receiver, "AI_RETRIEVAL")["attributes"][_ai.LOW_SCORE] == "0.7"
    assert only(receiver, "AI_MODEL")["attributes"][_ai.CONTEXT_LIMIT] == "8000"


def test_the_hints_are_off_when_their_limits_are(receiver):
    assert causeline.init(endpoint=receiver.url, token=TOKEN, low_score=0)
    Canned(found=[]).invoke("When?")
    FakeListLLM(responses=["Soon"]).invoke("When?")
    assert _ai.LOW_SCORE not in only(receiver, "AI_RETRIEVAL")["attributes"]
    assert _ai.CONTEXT_LIMIT not in only(receiver, "AI_MODEL")["attributes"]


def test_scored_retriever_records_scores(tracing, model):
    found = scored_retriever(filled_store(model), k=2).invoke(REFUNDS)
    assert [document.page_content for document in found][0] == REFUNDS
    search = only(tracing, "AI_RETRIEVAL")
    scores = [document["score"] for document in parsed(search, _ai.DOCUMENTS)]
    assert scores[0] > scores[1]
    assert search["attributes"]["db.vector.query.top_k"] == "2"
    assert search["name"] == "InMemoryVectorStore query"


def test_scored_retriever_works_asynchronously(tracing, model):
    asyncio.run(scored_retriever(filled_store(model)).ainvoke(REFUNDS))
    assert "score" in parsed(only(tracing, "AI_RETRIEVAL"), _ai.DOCUMENTS)[0]


def test_scored_retriever_leaves_the_stores_documents_alone(model):
    store = filled_store(model)
    scored_retriever(store).invoke(REFUNDS)
    assert "score" not in store.get_by_ids(["refunds"])[0].metadata


def test_scored_retriever_prefers_relevance_scores(tracing):
    class Store:
        def similarity_search_with_relevance_scores(self, query, k):
            return [(Document(page_content="text"), 0.8)]

        def similarity_search_with_score(self, query, k):
            raise AssertionError("this may be a distance")

    scored_retriever(Store()).invoke("When?")
    assert parsed(only(tracing, "AI_RETRIEVAL"), _ai.DOCUMENTS)[0]["score"] == 0.8


def test_retrieving_makes_one_search(tracing, model):
    store = filled_store(model)
    before = model.embedding_calls
    scored_retriever(store).invoke(REFUNDS)
    assert model.embedding_calls == before + 1


def test_traced_embeddings_show_their_calls(tracing, model):
    embedder = traced_embeddings(embeddings(model))
    embedder.embed_query("When?")
    embedder.embed_documents(["one", "two"])
    query, ingest = spans_of(tracing, "AI_MODEL")
    assert query["name"] == "embedding fake-embed"
    assert query["attributes"]["gen_ai.operation.name"] == "embedding"
    assert query["attributes"]["gen_ai.request.model"] == "fake-embed"
    assert parsed(query, _ai.EMBEDDING_INPUT) == ["When?"]
    assert parsed(ingest, _ai.EMBEDDING_INPUT) == ["one", "two"]


def test_traced_embeddings_work_asynchronously(tracing, model):
    embedder = traced_embeddings(embeddings(model))

    async def embed():
        await embedder.aembed_query("When?")
        await embedder.aembed_documents(["one"])

    asyncio.run(embed())
    assert len(spans_of(tracing, "AI_MODEL")) == 2


def test_traced_embeddings_join_the_request(tracing, model):
    chain = rag_chain(filled_store(model, traced_embeddings(embeddings(model))).as_retriever(), chat(model))
    with TestClient(app_with(chain)) as client:
        client.post("/ask", params={"question": "When?"})
    request = only(tracing, "REQUEST")
    joined = [span for span in spans_of(tracing, "AI_MODEL") if span["traceId"] == request["traceId"]]
    assert sorted(span["name"] for span in joined) == ["chat fake-chat", "embedding fake-embed"]


def test_repeated_embeddings_carry_the_same_input(tracing, model):
    # The Repeated embedding hint compares this attribute.
    embedder = traced_embeddings(embeddings(model))
    embedder.embed_query("same")
    embedder.embed_query("same")
    first, second = spans_of(tracing, "AI_MODEL")
    assert first["attributes"][_ai.EMBEDDING_INPUT] == second["attributes"][_ai.EMBEDDING_INPUT]


def test_traced_embeddings_pass_through_results_and_attributes(model):
    plain = embeddings(model)
    embedder = traced_embeddings(plain)
    assert embedder.embed_query("When?") == plain.embed_query("When?")
    assert embedder.model == "fake-embed"
    assert isinstance(embedder, Embeddings)


def test_a_failed_embedding_is_recorded_and_raised(tracing):
    class Broken(Embeddings):
        def embed_documents(self, texts):
            raise RuntimeError("no quota")

        def embed_query(self, text):
            raise RuntimeError("no quota")

    with pytest.raises(RuntimeError, match="no quota"):
        traced_embeddings(Broken()).embed_query("When?")
    assert only(tracing, "AI_MODEL")["status"] == "ERROR"
    assert only(tracing, "EXCEPTION")["attributes"]["exception.message"] == "no quota"


def test_embedding_text_needs_both_switches(receiver, model):
    assert causeline.init(endpoint=receiver.url, token=TOKEN, capture={"prompts", "completions"})
    traced_embeddings(embeddings(model)).embed_query(SECRET)
    assert SECRET not in str(only(receiver, "AI_MODEL"))


def test_nothing_happens_without_init(receiver, model):
    assert chat(model).invoke("When?").content == ANSWER
    assert traced_embeddings(embeddings(model)).embed_query("When?")
    assert causeline.stats() == {"sent": 0, "dropped": 0, "failed": 0}


def test_nothing_is_recorded_after_shutdown(receiver, model):
    assert causeline.init(endpoint=receiver.url, token=TOKEN)
    causeline.shutdown()
    chat(model).invoke("When?")
    assert receiver.spans == []


def test_a_failure_while_reading_loses_the_value_not_the_call(tracing, model, monkeypatch):
    def broken(*args, **kwargs):
        raise RuntimeError("reader bug")

    monkeypatch.setattr(_ai, "put_prompt", broken)
    monkeypatch.setattr(_ai, "put_completion", broken)
    monkeypatch.setattr(_ai, "put_documents", broken)
    assert chat(model).invoke("When?").content == ANSWER
    assert filled_store(model).as_retriever().invoke("When?")


def test_runs_that_never_end_cannot_grow_without_limit(tracing, monkeypatch):
    from uuid import uuid4

    handler = traced.CauselineCallbackHandler()
    monkeypatch.setattr(traced, "_MAX_OPEN_RUNS", 5)
    for _ in range(50):
        handler.on_chain_start({}, {}, run_id=uuid4())
    assert len(handler._runs) <= 5


def test_a_finished_chain_leaves_nothing_open(tracing, model):
    rag_chain(filled_store(model).as_retriever(), chat(model)).invoke("When?")
    assert traced._handler._runs == {}


def test_a_real_server_keeps_concurrent_calls_in_their_own_traces(tracing, model):
    chain = rag_chain(scored_retriever(filled_store(model)), chat(model))
    server = uvicorn.Server(uvicorn.Config(app_with(chain), host="127.0.0.1", port=0, log_level="warning"))
    # A thread has its own context, so this also proves the handler reaches one.
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 10
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.01)
    port = server.servers[0].sockets[0].getsockname()[1]

    async def call_all():
        async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{port}", timeout=30) as http:
            return await asyncio.gather(*(
                http.post(route, params={"question": f"question {index}"},
                          headers={"traceparent": f"00-{index + 1:032x}-{'b' * 16}-01"})
                for index, route in enumerate(["/ask", "/ask-sync"] * 8)
            ))

    responses = asyncio.run(call_all())
    server.should_exit = True
    thread.join(5)
    assert all(response.status_code == 200 for response in responses)
    assert causeline.flush(10)
    assert causeline.stats()["dropped"] == 0
    for index in range(16):
        trace = [span for span in tracing.spans if span["traceId"] == f"{index + 1:032x}"]
        assert sorted(span["kind"] for span in trace) == ["AI_MODEL", "AI_RETRIEVAL", "REQUEST"]
        search = next(span for span in trace if span["kind"] == "AI_RETRIEVAL")
        assert search["attributes"][_ai.SEARCH_QUERY] == f"question {index}"
