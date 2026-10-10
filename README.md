# spring-ai-test-tools-example

(This repository was renamed from `spring-ai-test-vcr-example` to match
`spring-ai-test-tools`, the library it demonstrates -- same rename, same reason.)

A standalone consumer of [spring-ai-test-tools](https://github.com/rifatcakir/spring-ai-test-tools)
(both its GitHub repo and Maven artifactId were renamed from `spring-ai-test-vcr` to match),
exercising it purely through its public API and its published Maven coordinates -- exactly
as an unrelated third-party project would. This exists as spring-ai-test-tools's final
pre-publish verification gate: if something in this repo breaks, the library's public API
surface broke for a real consumer, not just for its own internal test suite.

The library gives you a choice, per test: a real model, an explicit stub you write inline
or load from a file (WireMock-style, no hash, no lookup), or record/replay if you'd
rather capture a real answer once and replay it automatically forever. See
[`ThreeWaysToTestTheSameScenarioTest`](src/test/java/com/example/vcrdemo/ThreeWaysToTestTheSameScenarioTest.java)
below for all three side by side.

## `mvn test` needs no Ollama and no model

Every fixture this project needs is already committed under
`src/test/resources/llm-cache/`. Clone this repo, run `mvn test`, and it passes cold --
try it with Ollama stopped entirely (`docker stop ollama` or however you're running it) to
see for yourself. Two exceptions, both deliberate:
[`VcrAnnotationEscapeHatchTest`](#what-each-test-demonstrates) needs a real model (and is
excluded from the default build), and
[`CassetteServerRemoteStoreTest`](#server-and-client-shared-fixtures-on-a-cassette-server)
starts the Cassette Server in Docker (and is skipped when Docker isn't available). Neither
needs a model in the default build.

[`StubbingErrorScenariosTest`](#what-each-test-demonstrates) goes a step further than
"needs no Docker" -- it needs no fixture at all, and no Spring context either, since it
builds its `ChatModel` programmatically via `VcrStubs` instead of replaying anything
recorded.

## Setup

Nothing to install: the library comes from Maven Central, as an ordinary test dependency.

```xml
<dependency>
    <groupId>io.github.rifatcakir</groupId>
    <artifactId>spring-ai-test-tools</artifactId>
    <version>0.2.0</version>
    <scope>test</scope>
</dependency>
```

This project runs on the newest stable Spring AI (2.0.1) and Spring Boot (4.1.1), while the
library itself is built against Spring AI 2.0.0 and Spring Boot 4.0.0: the same tests and the
same committed fixtures pass on both, and on the Spring AI 2.1.0-M1 / Spring Boot 4.2.0-M2
milestones too (checked 2026-10-10 by overriding `spring-ai.version` and
`spring-boot.version`).

## What each test demonstrates

| File | What it shows |
|---|---|
| [`ThreeWaysToTestTheSameScenarioTest`](src/test/java/com/example/vcrdemo/ThreeWaysToTestTheSameScenarioTest.java) | **Start here.** The exact same prompt, answered three ways, side by side: a real model (tagged `integration`, needs Ollama), an inline `VcrStubs` response, and a file-sourced one (`src/test/resources/responses/acknowledged.txt`). All three hand back a plain `ChatModel`, so `ChatClient.builder(model)...` is identical in every one of them -- only which model gets constructed differs. The two stub methods need no Spring context at all, even though they happen to live in this `@SpringBootTest`-annotated class for easy side-by-side reading -- see the test's own Javadoc. |
| [`RecordReplayBasicsTest`](src/test/java/com/example/vcrdemo/RecordReplayBasicsTest.java) | The core loop: record once against a real model, replay identically forever after. `RECORD_OR_REPLAY`, the safe local-development default. |
| [`ReplayOnlySealedCiTest`](src/test/java/com/example/vcrdemo/ReplayOnlySealedCiTest.java) | `REPLAY_ONLY`, the mode CI actually runs with. A known prompt still replays normally; an unrecorded one throws `VcrCacheMissException` immediately, never touching the network. |
| [`CassetteServerRemoteStoreTest`](src/test/java/com/example/vcrdemo/CassetteServerRemoteStoreTest.java) | **Server and client.** The same `ChatClient` call as `ReplayOnlySealedCiTest`, with the fixture on a [Cassette Server](https://github.com/rifatcakir/spring-ai-test-tools-server) instead of on disk: `store: remote`, a URL and a namespace, no code changes. The real server image runs in Docker, seeded with the very fixture file `ReplayOnlySealedCiTest` replays. The application sets no mode; the server's namespace default (`REPLAY_ONLY`) decides, so an unrecorded prompt fails loudly and shows up in the server's misses log. See [below](#server-and-client-shared-fixtures-on-a-cassette-server). |
| [`VcrAnnotationEscapeHatchTest`](src/test/java/com/example/vcrdemo/VcrAnnotationEscapeHatchTest.java) | `@Vcr(mode = VcrMode.BYPASS)` -- one test opting out of a sealed `REPLAY_ONLY` context to reach a real model, without weakening the seal for anything else. Tagged `integration`, excluded from the default build (see below), and needs Ollama reachable every time it runs. |
| [`PromptNormalizerVolatileValuesTest`](src/test/java/com/example/vcrdemo/PromptNormalizerVolatileValuesTest.java) | A `VcrPromptNormalizer` bean (`RegexPromptNormalizer.ISO_DATE`) collapsing two prompts that differ only by which calendar date they name into one committed fixture. |
| [`FixtureRedactorSecretsTest`](src/test/java/com/example/vcrdemo/FixtureRedactorSecretsTest.java) | A `VcrFixtureRedactor` bean keeping a customer id out of the committed fixture -- read the JSON in `llm-cache/redactor/` yourself -- while two different customer ids still produce two separate fixtures, proving redaction can never cause a cache collision. |
| [`AutoConfigPropertiesYamlTest`](src/test/java/com/example/vcrdemo/AutoConfigPropertiesYamlTest.java) | The "quick start" story: zero `@Bean`, zero manual advisor wiring, just `spring.ai.test.vcr.*` properties. Contrast with the two tests above, which each need one extra bean on top of this baseline. |
| [`ToolCallingRecordReplayTest`](src/test/java/com/example/vcrdemo/ToolCallingRecordReplayTest.java) | How to test a real `@Tool` method deterministically. `VcrScope.INSIDE_TOOL_LOOP` caches one fixture per model turn, so a two-turn tool-calling conversation (the model asks to call the tool, the real tool runs, its result goes back, the model answers) records two fixtures, not one -- and the real `@Tool` method keeps running on every replay, not just on the first live call. |
| [`StructuredOutputRecordReplayTest`](src/test/java/com/example/vcrdemo/StructuredOutputRecordReplayTest.java) | How to test a `ChatClient...call().entity(Class)` structured-output call deterministically. POJO conversion happens client-side after the (possibly replayed) response comes back, so a replay converts to the same object a live call already produced. Uses `spec.useProviderStructuredOutput()` -- Ollama's native, JSON-schema-constrained decoding -- which this project's small model follows far more reliably than the default text-instruction-based converter. |
| [`SelfCorrectingStructuredOutputRecordReplayTest`](src/test/java/com/example/vcrdemo/SelfCorrectingStructuredOutputRecordReplayTest.java) | Spring AI 2.0's new self-correcting structured output -- `.entity(Class, spec -> spec.validateSchema())` -- needs zero extra spring-ai-test-tools configuration on top of `StructuredOutputRecordReplayTest` above. `StructuredOutputValidationAdvisor`'s retry loop (default order `LOWEST_PRECEDENCE - 2000`) sits *below* where this library's advisor runs, so the whole self-correction loop -- real retries included -- is recorded as one fixture: VCR only ever sees the loop's final, validated answer. Deliberately uses the *default* text-instruction converter (not `useProviderStructuredOutput()`) since `validateSchema()` exists to catch and correct exactly the kind of malformed JSON that converter is more prone to. See the test's own Javadoc for an honest note on what this specific recording actually needed (no retry, as it happens) and a pointer to the main library's own e2e test, which forces a real multi-attempt loop deterministically to prove the mechanism itself. |
| [`RagAgentRecordReplayTest`](src/test/java/com/example/vcrdemo/RagAgentRecordReplayTest.java) | Testing a RAG agent's *generation* step deterministically -- retrieval is a separate concern this library doesn't cache, but whatever retrieval hands back becomes a system message injected into the prompt, exactly like a `QuestionAnswerAdvisor`-style pipeline produces by the time a request reaches this library's own advisor. The two "retrieved documents" describe a fictional company's fictional return policy on purpose: a correct-looking answer to a real-world fact would prove nothing, since the model could have guessed it, but there is no way `llama3.2:1b` has an invented 45-day/original-packaging policy memorized. One of the two documents is deliberately irrelevant too, proving the model picked the right one out of more than one candidate. |
| [`MultiToolAgentRecordReplayTest`](src/test/java/com/example/vcrdemo/MultiToolAgentRecordReplayTest.java) | What `ToolCallingRecordReplayTest`'s single tool can't show: an agent with a genuine *choice*. Two `@Tool` methods are registered -- `getOrderStatus` and an irrelevant decoy, `getWeatherForCity` -- and the fixture proves the model called only the right one, out of more than one candidate, with both tools' invocation counters staying at zero on replay (`VcrToolMode.REPLAY_FROM_CASSETTE`, the default isolation). Kept to a single string argument per tool deliberately: `llama3.2:1b` reliably produces a real structured tool call for that shape, but not for the two-argument version tried first -- see the test's own Javadoc. |
| [`PromptRegressionSuiteTest`](src/test/java/com/example/vcrdemo/PromptRegressionSuiteTest.java) | The story design rule #5 in the main library tells but no other test here demonstrates end to end: a committed fixture *is* a prompt regression check. Three unrelated prompts are this project's small "golden suite," each answer pinned exactly like a snapshot test. A fourth test proves the payoff concretely -- reshaping one golden prompt by a single word (`"the number"` -> `"the numeral"`) is a different SHA-256 hash, so it is a loud, immediate `VcrCacheMissException` under sealed `REPLAY_ONLY`, never a silently-reused "close enough" answer. |
| [`AssertionsShowcaseTest`](src/test/java/com/example/vcrdemo/AssertionsShowcaseTest.java) | spring-ai-test-tools's Assertions layer (`VcrAssertions.assertThat(...)`), used against the tool-calling and structured-output fixtures above -- read straight off disk via the library's own public `VcrTrackStore`/`VcrTrackMapper`, no new recording. Reads the tool-calling fixture's *first turn* specifically, because that turn's response is the model's still-pending tool call, before Spring AI's built-in tool loop resolves it -- see the test's own Javadoc, and `hasToolCall`'s Javadoc in the library, for why a normal `ChatClient.tools(...).call()`'s *final* response can't be asserted on the same way. |
| [`EmbeddingRecordReplayTest`](src/test/java/com/example/vcrdemo/EmbeddingRecordReplayTest.java) | Record/replay for `EmbeddingModel` (R4) -- the same story `RecordReplayBasicsTest` tells for chat, applied to `embed(String)` instead. `EmbeddingModel` has no advisor chain, so interception is a `BeanPostProcessor` wrapping the autoconfigured `OllamaEmbeddingModel` bean directly, gated by its own `spring.ai.test.vcr.embedding.enabled` flag -- invisible from this test's own code, which just autowires `EmbeddingModel` like any other consumer would. Asserts the replayed vector is *exactly* (not "same length") the recorded one -- `llama3.2:1b`'s real 2048-dimension output, confirmed by a real call, not a dedicated embedding model. |
| [`EvaluatorRecordReplayTest`](src/test/java/com/example/vcrdemo/EvaluatorRecordReplayTest.java) | Spring AI's own `Evaluator` mechanism (`RelevancyEvaluator`) becomes deterministic for free -- not a spring-ai-test-tools feature, a usage pattern this library's design already supported. `RelevancyEvaluator` is built from the same `ChatClient.Builder` already customized elsewhere in this project; its internal judge call records and replays exactly like any other `ChatClient` call, with no new code. Read the test's own Javadoc for an honest note on what the small model actually judged. |
| [`SemanticSimilarityRecordReplayTest`](src/test/java/com/example/vcrdemo/SemanticSimilarityRecordReplayTest.java) | `spring-ai-test-tools`'s Assertions layer, A2: `VcrAssertions.assertThat(response).usingEmbeddingModel(embeddingModel).isSemanticallySimilarTo(expected, threshold)` -- "is this answer close enough in meaning," deterministically, since both embedding calls go through the same Recorder-backed `EmbeddingModel` `EmbeddingRecordReplayTest` (R4) already shows. Uses an explicit `0.85` threshold rather than the library's own `0.7` default -- read the test's own Javadoc for the empirical reason (`llama3.2:1b`'s embeddings compress paraphrase/unrelated-sentence similarity into a narrow range that the default doesn't reliably separate). |
| [`EvaluatorLiveDriftCheckTest`](src/test/java/com/example/vcrdemo/EvaluatorLiveDriftCheckTest.java) | E2's "two modes, one evaluator" story, made concrete: the exact same `RelevancyEvaluator`, the exact same `EvaluationRequest` `EvaluatorRecordReplayTest` already replays deterministically, but this test uses `@Vcr(mode = VcrMode.BYPASS)` to reach the real model instead -- proving the committed fixture is neither read nor overwritten. Tagged `integration`, excluded from the default build, needs Ollama reachable every time it runs -- this is the live drift/quality-check path spring-ai-test-tools's `docs/E2-EVALUATION-MODES-PRD.md` documents, deliberately never run in this project's own CI. |
| [`StreamingRecordReplayTest`](src/test/java/com/example/vcrdemo/StreamingRecordReplayTest.java) | Record/replay for `ChatClient...stream()` (R3) -- the same record-once-replay-forever story `RecordReplayBasicsTest` tells for `.call()`, applied to a streamed `Flux<String>` instead. Asserts the exact recorded chunk sequence (`containsExactly("Yes", ".")`), not just the joined text, since chunk-boundary fidelity is the entire reason `VcrStreamTrack` stores raw chunks rather than a single aggregated answer. |
| [`StubbingErrorScenariosTest`](src/test/java/com/example/vcrdemo/StubbingErrorScenariosTest.java) | spring-ai-test-tools's Stub layer (`VcrStubs.chatModel()`), for exactly what no other test here can show: a scenario no real model will reliably reproduce on demand. A `failingWith(...)` stub proves your own error-handling code reacts correctly to a model timeout without waiting for a real one; a `withFinishReason("length")` stub lets `VcrAssertions.assertThat(response).hasFinishReason("length")` check truncation handling deterministically. Unlike every other test in this project, this one needs no fixture, no cache directory, no Ollama, and -- unlike every `@SpringBootTest` elsewhere here -- no Spring context at all: it's a plain JUnit test against a plain Java object. |

## Server and client: shared fixtures on a Cassette Server

Fixtures under `src/test/resources` serve a laptop and CI. A deployed test environment, or
several services sharing one set of recordings, keep them on a
[Cassette Server](https://github.com/rifatcakir/spring-ai-test-tools-server) instead. The library is the client: hashing, record/replay, tool
isolation and redaction stay in the application, and the server only stores the fixtures.
Switching is configuration only:

```yaml
spring:
  ai:
    test:
      vcr:
        enabled: true
        store: remote
        remote:
          url: http://localhost:9090
          namespace: example
```

[`CassetteServerRemoteStoreTest`](src/test/java/com/example/vcrdemo/CassetteServerRemoteStoreTest.java)
shows it automatically, with the server in Docker. To try it by hand instead, with just Java:

```bash
# 1. The server: one jar, no Docker needed
curl -LO https://github.com/rifatcakir/spring-ai-test-tools-server/releases/download/v0.2.0/spring-ai-test-tools-server-0.2.0.jar
java -jar spring-ai-test-tools-server-0.2.0.jar --storage-dir=./cassettes --admin-token=secret

# 2. Seed namespace "example" with this project's committed fixtures, unchanged
#    (a server namespace has exactly the library's file layout)
mkdir -p cassettes/example && cp -r src/test/resources/llm-cache/basics cassettes/example/llm-cache

# 3. Run an existing test against the server instead of the files (no model needed)
mvn test -Dtest=RecordReplayBasicsTest -Dspring.ai.test.vcr.store=remote \
  -Dspring.ai.test.vcr.remote.url=http://localhost:9090 -Dspring.ai.test.vcr.remote.namespace=example

# 4. See what the server holds
curl -H "Authorization: Bearer secret" http://localhost:9090/__admin/namespaces/example/fixtures
```

The test passes with Ollama stopped: both calls are `VCR CACHE HIT`s served by the server. With
a model running and a namespace that lacks a fixture, the same configuration records to the
server instead; `GET /__admin/namespaces/example/export` returns the recordings as a zip in the
`src/test/resources` layout, ready to commit.

Use the same server version as the library (0.2.0 here).

## Running the excluded integration test

```bash
mvn test -Pintegration
```

Needs Ollama reachable at `http://localhost:11434` with `llama3.2:1b` pulled (see
`src/test/resources/application.yml`) -- both `VcrAnnotationEscapeHatchTest` and
`EvaluatorLiveDriftCheckTest` need it, since both use `@Vcr(mode = VcrMode.BYPASS)` to
reach the real model. Every other test needs none of that.

## How the fixtures were produced

1. Start a real Ollama with `llama3.2:1b` available locally.
2. Temporarily set the mode property in `ReplayOnlySealedCiTest.AKnownPromptStillReplays`,
   `PromptNormalizerVolatileValuesTest`, `FixtureRedactorSecretsTest`,
   `ToolCallingRecordReplayTest`, `StructuredOutputRecordReplayTest`,
   `SelfCorrectingStructuredOutputRecordReplayTest`, `RagAgentRecordReplayTest`,
   `MultiToolAgentRecordReplayTest`, and `PromptRegressionSuiteTest` from `REPLAY_ONLY` to
   `RECORD_OR_REPLAY`
   (`RecordReplayBasicsTest` and `AutoConfigPropertiesYamlTest` already use
   `RECORD_OR_REPLAY` permanently -- no change needed for those). For
   `PromptRegressionSuiteTest` specifically, run only its three golden-prompt methods
   while recording (`-Dtest=PromptRegressionSuiteTest#goldenPrompt...`) -- its fourth
   test, the drift-fails-loud proof, must never get a fixture of its own; that is the
   entire point of what it demonstrates.
3. Run `mvn test` once. Every fixture gets written under
   `src/test/resources/llm-cache/<test-name>/`. `ToolCallingRecordReplayTest` writes
   *two* fixtures for its one tool call -- `VcrScope.INSIDE_TOOL_LOOP` caches one per
   model turn, not one for the whole round trip.
4. **Read the generated JSON before committing it** -- exactly as spring-ai-test-tools's own
   `CLAUDE.md` insists on for its own fixtures. This is where a redaction bug was actually
   caught while building this project: the first attempt redacted `request.messages()`
   but forgot that `canonicalRequest` is a *separate* field that also embeds the raw
   message text, so the customer id was still leaking through it. Reading the fixture,
   not just trusting the assertions, is what caught that. For the tool-calling fixtures
   specifically, check that the tool call's function name and arguments are exactly what
   you expect (`getOrderStatus` / `{"orderId":"ORD-4471"}`) and that the tool's response
   made it into the second turn's fixture. For the structured-output fixture, check that
   `request.structuredOutput` actually carries the JSON schema (confirms the cache key is
   sensitive to the target type, not just the prompt text) and that the recorded answer is
   sensible -- `llama3.2:1b` is small enough that the first couple of recording attempts
   here produced a response that just echoed the JSON schema back instead of filling in
   values, and a later one produced a technically-valid but silly `estimatedDays` in the
   hundreds of thousands; a small nudge in the prompt (an explicit range) was enough to get
   a clean answer. This is a small-model prompting quirk, not anything to do with caching
   correctness -- the round trip itself was never in question, only whether the *content*
   was worth committing to a fixture someone will read in a PR. The tool-calling fixtures
   needed several recording attempts for the same reason: `llama3.2:1b` doesn't reliably
   invoke the tool through Spring AI's structured tool-calling protocol on every attempt --
   some attempts produced a plain-text string that merely *looked* like a tool call
   (`toolCalls` empty in the response), and one produced a real tool call with garbled
   arguments. Re-running the same test (not changing the mechanism under test) until a
   clean, genuine two-turn exchange came back is the same "read it, don't just trust that
   `mvn test` was green" discipline, applied to recording quality rather than caching
   correctness.
5. Flip the five properties in step 2 back to `REPLAY_ONLY`, then run `mvn test` again
   with Ollama stopped to confirm nothing regressed.
6. Commit the fixtures.

All fixtures here were re-recorded once already, for a reason worth knowing if you're
touching a tool or `entity()` schema: `spring-ai-test-tools` versions before schema `"4"`
hashed a tool's input schema and an `entity()` call's format instructions/JSON schema
as-is, and Jackson pretty-prints that text using the *recording machine's* own line
separator (`\r\n` on Windows, `\n` on Linux/macOS) -- so a fixture recorded on Windows
(as these originally were) failed to replay on this project's own Linux CI. `spring-ai-test-tools`
now normalizes those line endings before hashing (and in what it stores), so this is now
purely historical -- but it's why you'll see schema `"4"` throughout
`src/test/resources/llm-cache/`, not `"3"`.

`EmbeddingRecordReplayTest`'s fixture (`src/test/resources/llm-cache-embedding/`) was
produced the same way, but through a separate property (`spring.ai.test.vcr.embedding.mode`,
not the top-level `spring.ai.test.vcr.mode` the steps above flip) since `EmbeddingModel`
interception is gated independently -- see spring-ai-test-tools's own
`docs/R4-EMBEDDING-INTERCEPTION.md`. Worth knowing if you read this one fixture in a PR:
it is the only fixture in this whole project that is not meaningfully human-reviewable --
its `response.embeddings[].vector` field is `llama3.2:1b`'s real 2048-number output.
Storing the full vector (not a hash of it) is required, not a stylistic choice: a hash
can never be replayed back into a usable vector, which defeats the entire reason this
fixture type exists. The pleasant surprise: Jackson renders a `float[]` as a single
compact line rather than one number per line, so the whole fixture is 22 lines, not the
thousands a naive prediction might expect.

`StreamingRecordReplayTest`'s fixture (`src/test/resources/llm-cache-stream/`) was produced
the same way as the top-level `.call()` fixtures (step 2's `RECORD_OR_REPLAY`, a real
`llama3.2:1b` run, then flipped back and re-verified with Ollama stopped), but reading it is
worth doing differently: unlike every other fixture here, its `response.chunks` array is
the point of the file. The recorded stream for "Reply with exactly one word: acknowledged"
came back as three raw chunks -- `"Yes"`, `"."`, and a final chunk carrying no text but the
`finishReason`/usage metadata -- with `response.aggregateText` (`"Yes."`) alongside purely
for a reviewer's convenience, never read back for replay. The test's own assertion checks
the raw chunk list via `containsExactly("Yes", ".")` (the empty finishing chunk isn't
surfaced by `ChatClient.StreamResponseSpec.content()`), specifically to prove replay
reproduces the exact chunk sequence a live stream produced, not merely the same final text
— see `docs/R3-STREAMING-PRD.md` in the main library's repo for why that distinction is the
entire design point of this fixture type.

`EvaluatorRecordReplayTest`'s fixture (`src/test/resources/llm-cache/evaluator/`) has two
things worth knowing if you touch it. First, `llama3.2:1b` answered `RelevancyEvaluator`'s
judge prompt inconsistently across several real re-recording attempts for the exact same
query/response/context -- "No.", "NO.", and once "YES" -- with temperature left at
Ollama's own default (this project doesn't pin it for chat calls). The committed fixture
records whichever answer came back on the recording that was actually kept ("No."),
rather than discarding it in favor of a "nicer" one -- the point of this test is that a
replay is exactly what was recorded, not that the judge's opinion is one a human would
agree with. Second, and more load-bearing: this test's own `@TestConfiguration` registers
a `VcrPromptNormalizer` that collapses `\r\n`/`\r` to `\n` in message text -- without it,
this fixture recorded on Windows genuinely missed on this project's own Linux CI, the
same cross-platform line-ending bug spring-ai-test-tools itself already fixed for tool
schemas, but showing up here in a place that fix doesn't cover (`RelevancyEvaluator`'s
own `PromptTemplate`-rendered judge prompt, which looks like ordinary user-message text
to the cache key generator). See the test's own Javadoc for why the fix lives in this
project's test code rather than in spring-ai-test-tools itself.

`SemanticSimilarityRecordReplayTest`'s two fixtures live in their own directory,
`src/test/resources/llm-cache-embedding-semantic-similarity/` -- a sibling of
`EmbeddingRecordReplayTest`'s `llm-cache-embedding/`, not a subdirectory of it. That
distinction actually mattered here: an earlier attempt nested it *inside*
`llm-cache-embedding/`, which broke `EmbeddingRecordReplayTest`'s own "exactly one
fixture" assertion -- `Files.list()` on the parent directory counts a subdirectory as an
entry too, so `EmbeddingRecordReplayTest` suddenly saw two entries instead of one. Fixed
by keeping the two test's cache directories as siblings, the same way `llm-cache/<test-name>/`
already keeps every chat fixture directory separate.

`EvaluatorLiveDriftCheckTest` needed **no new recording at all** -- it deliberately
reuses `EvaluatorRecordReplayTest`'s already-committed fixture and cache directory,
`@Vcr(mode = VcrMode.BYPASS)` simply bypasses it instead of reading it. That is the
entire point: the same fixture, read deterministically by one test and ignored on
purpose by the other, depending only on which mode each test asks for.

`SelfCorrectingStructuredOutputRecordReplayTest`'s fixture
(`src/test/resources/llm-cache/self-correcting-structured-output/`) was produced the same
way as the top-level `.call().entity()` fixtures (step 2's `RECORD_OR_REPLAY`, a real
`llama3.2:1b` run, then flipped back to `REPLAY_ONLY` and re-verified with Ollama stopped).
Worth knowing if you touch it: on this particular recording, `llama3.2:1b` produced valid
JSON matching the schema on its *first* attempt -- `StructuredOutputValidationAdvisor`'s
retry loop never actually looped. That's a genuine, unforced outcome (this project doesn't
retry recordings until an interesting one happens), not a sign the self-correction feature
went untested -- see the test's own Javadoc, which points to the main library's
`OllamaStructuredOutputValidationEndToEndTests` for a fixture that *does* force and prove a
real multi-attempt retry, deterministically, via a schema requiring a value the model is
never shown (so it can't be satisfied by luck).

To re-record after a real change (a prompt, a model, spring-ai-test-tools itself), delete
the relevant fixture file(s) and repeat from step 2.

## Requirements

Java 21 · Spring Boot 4.0+ · Spring AI 2.0+ (this project: Spring AI 2.0.1, Spring Boot 4.1.1) ·
Docker only for `CassetteServerRemoteStoreTest`, which is skipped without it.

## Licence

Apache-2.0
