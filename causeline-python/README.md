# Causeline for Python

Puts a FastAPI service in the same trace as the React click and the Spring Boot request. Spans go to the [Causeline](https://github.com/causeline/causeline) embedded in your Spring Boot app, so one click shows on one timeline.

Development and QA only. It does nothing until you call `init`, and it stays off in a production environment.

```python
import causeline
from fastapi import FastAPI

causeline.init(endpoint="http://localhost:8080", token="...", service="ai-service")
app = FastAPI()
causeline.instrument(app)
```

`token` is your Spring app's `causeline.access-token`. `endpoint` and `token` can also come from `CAUSELINE_ENDPOINT` and `CAUSELINE_TOKEN`, and `service` from `CAUSELINE_SERVICE_NAME`.

For any other ASGI app, wrap it: `app = causeline.CauselineMiddleware(app)`.

## LangChain

With `langchain-core` installed, `init` adds a handler to every run, so your chains need no changes. Chat calls and retriever runs become spans, and the RAG panel and hints work as they do for Spring AI.

LangChain's callbacks don't give chunk scores or embedding calls. Two optional helpers add them:

```python
from causeline.langchain import scored_retriever, traced_embeddings

store = InMemoryVectorStore(traced_embeddings(embeddings))
retriever = scored_retriever(store, k=4)
```

A document's score is also read from `metadata["score"]` or `metadata["relevance_score"]`, which many rerankers set. Without a score, none is shown and the *Low relevance* hint stays silent. Without `traced_embeddings` there are no embedding spans, and the *Repeated embedding* hint stays silent. A direct `store.similarity_search(...)` call shows no span: only retriever runs do.

`low_score` (default `0.5`, `0` turns it off) and `context_limit` (off unless set) tune the hints.

## What leaves your service

- Requests: method, path, route, status, query with sensitive values hidden, and exceptions. Headers and bodies are not captured.
- AI text is sent only to a Causeline on the same machine. To any other host it is withheld, unless you pass `send_ai_text=True`. Exception text is scrubbed of credentials on the way to another host.
- `capture={"prompts", "completions", "documents"}` chooses which AI text is captured at all. `redact_keys=["internal_id"]` hides more query values, and values of those keys in AI text, even locally.

Spans are sent from a background thread and dropped if the queue fills. `causeline.stats()` reports what was sent, dropped and failed.
