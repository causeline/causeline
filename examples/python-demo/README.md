# Python demo

The shop's RAG assistant as a FastAPI service with LangChain. `spring-demo` calls it, so one click runs React, Spring, Python and the model, and shows as one trace in Causeline. It uses the real OpenAI client against `spring-demo`'s fake model, so it needs no API key and no network.

## Run it

Install once, from this folder (the requirements use a relative path):

```bash
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt
```

Start `spring-demo` with a known token, from the repository root:

```bash
./mvnw -pl examples/spring-demo spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--causeline.access-token=dev
```

Start this service, from this folder:

```bash
CAUSELINE_TOKEN=dev .venv/Scripts/python -m uvicorn app:app --port 8091
```

In `cmd`, run `set CAUSELINE_TOKEN=dev` first, then the same command with backslashes.

Start the page, from the repository root:

```bash
npm run dev -w react-demo
```

Open http://localhost:5173, tick **Ask the Python service** in the Assistant panel and pick a scenario. Then open the trace with the `Causeline UI:` link from the Spring log.

On Linux and macOS, use `.venv/bin/python`.

## Settings

| Variable | Default | Meaning |
| --- | --- | --- |
| `CAUSELINE_ENDPOINT` | `http://localhost:8080` | The Spring app that holds the trace |
| `CAUSELINE_TOKEN` | none | Its access token. Without one, the service runs untraced |
| `DEMO_MODEL_URL` | `http://127.0.0.1:8090/v1` | The fake model, which starts with `spring-demo`'s first question |

`spring-demo` finds this service through `demo.python.url` (default `http://localhost:8091`). If this service isn't running, `POST /api/ask/python` answers 503, and the Spring assistant keeps working.
