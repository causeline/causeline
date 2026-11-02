# SPDX-License-Identifier: Apache-2.0
"""
The shop's RAG assistant again, as a FastAPI service with LangChain. spring-demo calls it, so one
click runs React, Spring, Python and the model. It uses the real OpenAI client against spring-demo's
fake model, so it needs no key and no network.
"""
import os
import threading

import causeline
from causeline.langchain import scored_retriever, traced_embeddings
from fastapi import FastAPI
from langchain_core.documents import Document
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.vectorstores import InMemoryVectorStore
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from pydantic import BaseModel

causeline.init(endpoint=os.getenv("CAUSELINE_ENDPOINT", "http://localhost:8080"), service="python-assistant")

# Not localhost: on Windows each new connection would try IPv6 for two seconds first.
MODEL_URL = os.getenv("DEMO_MODEL_URL", "http://127.0.0.1:8090/v1")
TOP_K = 3

app = FastAPI()
causeline.instrument(app)

embeddings = OpenAIEmbeddings(model="fake-embed", api_key="demo-model-key", base_url=MODEL_URL,
                              check_embedding_ctx_length=False)
store = InMemoryVectorStore(traced_embeddings(embeddings))
retriever = scored_retriever(store, k=TOP_K)
prompt = ChatPromptTemplate.from_messages([("system", "Answer only from this context:\n{context}"), ("human", "{question}")])
plain = ChatOpenAI(model="fake-chat", api_key="demo-model-key", base_url=MODEL_URL)
tight = ChatOpenAI(model="fake-chat", api_key="demo-model-key", base_url=MODEL_URL, max_completion_tokens=5)

_seeded = False
_seed_lock = threading.Lock()


def _ingest() -> None:
    store.add_documents([
        Document(id="refunds", page_content="Refunds are issued within 14 days of delivery.", metadata={"source": "policy.md"}),
        Document(id="receipts", page_content="A refund needs the original receipt.", metadata={"source": "policy.md"}),
        Document(id="shipping", page_content="Standard shipping takes three working days.", metadata={"source": "shipping.md"}),
        Document(id="hours", page_content="Support answers on weekdays from nine to five.", metadata={"source": "support.md"}),
    ])


def seed() -> None:
    """On first use, because the fake model starts with spring-demo's first question."""
    global _seeded
    with _seed_lock:
        if not _seeded:
            # A new thread has no current span, so ingesting isn't part of the first question's trace.
            ingest = threading.Thread(target=_ingest)
            ingest.start()
            ingest.join()
            _seeded = True


class Question(BaseModel):
    question: str
    mode: str | None = None


@app.post("/ask")
def ask(question: Question) -> dict[str, str]:
    """``cut-off`` sets a tight output limit, and ``reranked`` keeps only the best chunk."""
    seed()
    found = retriever.invoke(question.question)
    kept = found[:1] if question.mode == "reranked" else found
    context = "\n".join(document.page_content for document in kept)
    model = tight if question.mode == "cut-off" else plain
    chain = prompt | model | StrOutputParser()
    return {"answer": chain.invoke({"context": context, "question": question.question})}
