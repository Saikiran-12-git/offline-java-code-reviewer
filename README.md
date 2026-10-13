# Offline Java Code Reviewer · Local Lens

A Java 21 / Spring Boot app that reviews small Java snippets using a downloaded
Ollama model on your computer. A browser interface streams findings as they arrive.
Choose bugs, security, performance, or readability. No external AI API key is needed.

## Quick start

Prerequisites: JDK 21, Maven 3.6.3+, and [Ollama](https://ollama.com/download).
Check `java -version` and `mvn -version`; both should use Java 21.
The first build and model download require internet access. Download dependencies
and the model before going offline.

1. Download the default model while Ollama is running:

   ```bash
   ollama pull qwen2.5-coder:3b
   ```

2. Disable Ollama cloud features before submitting code. With a terminal-managed
   server, stop any existing Ollama server and start:

   ```bash
   OLLAMA_NO_CLOUD=1 ollama serve
   ```

   On macOS when using the Ollama desktop app, set
   `launchctl setenv OLLAMA_NO_CLOUD 1`, then quit and restart Ollama.
   On Windows set the user environment variable `OLLAMA_NO_CLOUD` to `1`, then
   quit and restart Ollama. The Java app cannot configure an already-running
   Ollama process. Do not choose a cloud model or an alias backed by a remote model.

3. Clone, build, and launch:

   ```bash
   git clone https://github.com/Saikiran-12-git/offline-java-code-reviewer.git
   cd offline-java-code-reviewer
   mvn verify
   java -jar target/offline-java-code-reviewer-1.0.0.jar
   ```

4. Open http://127.0.0.1:8080, click **Load example**, choose a focus, and review.
   The **Stop** button cancels the browser request; partial output remains visible.

For development, use `mvn spring-boot:run`. For a previously built artifact,
`java -jar ...` does not need Maven or internet access. No CDN, remote fonts, or
external JavaScript is used by the interface.

## How it works

Browser → Spring Boot WebFlux → local Ollama `/api/chat` → downloaded model.

The browser posts code and focus to `/api/reviews`. The service adds a review
instruction, streams Ollama's newline-delimited JSON, and emits `delta`, `done`,
or `error` events. Model output is shown as plain text, never executed or rendered
as HTML. Code is sent only to the configured loopback origin; redirects are not enabled.
Snippets are not stored or logged by the application. There is no cloud fallback.

Example API request:

```bash
curl -N http://127.0.0.1:8080/api/reviews \
  -H 'Content-Type: application/json' \
  -d '{"code":"class Example { int divide(int a, int b) { return a / b; } }","focus":"BUGS"}'
```

Example response shape (illustrative, not a recorded model run):

```json
{"type":"delta","text":"Check whether b can be zero."}
{"type":"done","text":""}
```

Input validation errors use HTTP 400. Once streaming begins, upstream failures are
returned as `error` events, even when the HTTP status is already 200. Clients must
check event types. A stream ending without Ollama's `done` frame is an error.

## Configuration

| Setting | Default | Purpose |
| --- | --- | --- |
| `OLLAMA_URL` | `http://127.0.0.1:11434` | Loopback HTTP origin only |
| `OLLAMA_MODEL` | `qwen2.5-coder:3b` | Downloaded local model |
| `review.idle-timeout-seconds` | 180 | Maximum gap waiting for upstream data |
| `review.total-timeout-seconds` | 300 | Whole-review deadline |
| `server.port` | 8080 | Browser interface port |

Example for a larger local model, downloaded separately:

```bash
OLLAMA_MODEL=qwen2.5-coder:7b java -jar target/offline-java-code-reviewer-1.0.0.jar
```

Override Spring properties with flags such as `--server.port=8081`.
The app binds to `127.0.0.1`. It rejects non-loopback model endpoints and model
names containing `cloud`; additionally disable cloud features in Ollama because
model names alone cannot prove where a custom alias runs.

## Engineering constraints

- Input limit: 6,000 UTF-8 bytes, with JSON request buffering limited to 64 KiB.
- Context request: 8,192 tokens; output budget: 1,024 tokens. These conservative
  snippet limits leave room for instructions and output; they are not a tokenizer.
- One active review per app instance avoids overwhelming a local model. A second
  request gets a useful error event. Completion, error, and cancellation release
  the slot. Server cancellation does not guarantee immediate model unloading.
- Idle and total deadlines prevent a stalled review from blocking later reviews.
- Instructions treat code and comments as untrusted data. This reduces prompt
  injection risk but is not a guarantee of model adherence.
- Browser origins from unrelated sites are rejected. This is a trusted single-user
  local application, with no authentication or multi-user isolation.

## Tests and verification

`mvn verify` builds the executable JAR and runs JUnit tests. A real local HTTP mock
implements Ollama's streaming response shape; no model or Ollama install is
required to run tests. Tests cover valid streaming, final-chunk content,
input rejection, malformed responses, HTTP failures, incomplete streams, origin
checks, serving the UI, loopback configuration, deadlines, and cancellation.

The browser's stream handling can also be checked with Node.js 20+:

```bash
node --test src/test/js/app.test.cjs
```

These dependency-free UI logic tests cover split JSON frames, text rendering,
UTF-8 input limits, upstream errors, incomplete streams, and Stop behavior. They
use a minimal DOM stub and do not claim a visual browser test.

GitHub Actions builds and tests with Java 21 and runs the UI logic tests on every push and pull request.
Tests verify integration behavior, not model reasoning quality. No model quality,
latency benchmark, or quantization comparison is claimed. See
[EVALUATION.md](EVALUATION.md) for a reproducible manual quality check.

## Troubleshooting

- **Connection error:** start Ollama and confirm `ollama list` works.
- **HTTP 404:** run `ollama pull qwen2.5-coder:3b` or download the configured model.
- **Timeout:** try a shorter method or a smaller model; initial model loading is slower.
- **Port in use:** use `--server.port=8081`, then open that port in your browser.
- **Invalid target release 21:** ensure Maven uses JDK 21, not an older JDK.
- **Over input budget:** review a smaller method; this app does not silently truncate code.

## Interview explanation

"I built a Java application where local inference is an architectural constraint.
I used WebFlux to forward a model stream, validated input budgets, bounded review
concurrency, and handled cancellation and incomplete responses. I tested the
HTTP integration without a GPU using a mock Ollama server. I separate those tests
from evaluating whether the model finds real bugs."

Model suggestions can miss issues or report false positives. Review the findings
and run your normal compiler, tests, and static analysis before changing code.

References: [Ollama chat API](https://docs.ollama.com/api/chat),
[Ollama local/cloud configuration](https://docs.ollama.com/faq),
[Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
