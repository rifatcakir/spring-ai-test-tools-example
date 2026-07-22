# spring-ai-test-tools-example

(This repository was renamed from `spring-ai-test-vcr-example` to match
`spring-ai-test-tools`, the library it demonstrates -- same rename, same reason.)

A standalone consumer of [spring-ai-test-tools](https://github.com/rifatcakir/spring-ai-test-tools)
(both its GitHub repo and Maven artifactId were renamed from `spring-ai-test-vcr` to match),
exercising it purely through its public API and its published Maven coordinates -- exactly
as an unrelated third-party project would. This exists as spring-ai-test-tools's final
pre-publish verification gate: if something in this repo breaks, the library's public API
surface broke for a real consumer, not just for its own internal test suite.

## `mvn test` needs no Docker, no Ollama, and no network

Every fixture this project needs is already committed under
`src/test/resources/llm-cache/`. Clone this repo, run `mvn test`, and it passes cold --
try it with Ollama stopped entirely (`docker stop ollama` or however you're running it) to
see for yourself. One test is the deliberate exception: see
[`VcrAnnotationEscapeHatchTest`](#what-each-test-demonstrates) below.

## Setup

spring-ai-test-tools is not yet published to Maven Central. Until it is, install it into
your local repository first:

```bash
cd ../spring-ai-test-tools   # or wherever you cloned it
mvn clean install          # not -Prelease -- no signing needed just to consume it locally
```

This project's `pom.xml` then depends on it as an ordinary, fixed-version dependency:

```xml
<dependency>
    <groupId>io.github.rifatcakir</groupId>
    <artifactId>spring-ai-test-tools</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

(The published Maven artifactId is `spring-ai-test-tools`; the GitHub repo it's built from was renamed to match, from `spring-ai-test-vcr` to `spring-ai-test-tools`.)

**Nothing here changes once the library is actually published.** The coordinate and
version stay identical; Maven just starts resolving it from Central instead of your local
`~/.m2/repository`.

## What each test demonstrates

| File | What it shows |
|---|---|
| [`RecordReplayBasicsTest`](src/test/java/com/example/vcrdemo/RecordReplayBasicsTest.java) | The core loop: record once against a real model, replay identically forever after. `RECORD_OR_REPLAY`, the safe local-development default. |
| [`ReplayOnlySealedCiTest`](src/test/java/com/example/vcrdemo/ReplayOnlySealedCiTest.java) | `REPLAY_ONLY`, the mode CI actually runs with. A known prompt still replays normally; an unrecorded one throws `VcrCacheMissException` immediately, never touching the network. |
| [`VcrAnnotationEscapeHatchTest`](src/test/java/com/example/vcrdemo/VcrAnnotationEscapeHatchTest.java) | `@Vcr(mode = VcrMode.BYPASS)` -- one test opting out of a sealed `REPLAY_ONLY` context to reach a real model, without weakening the seal for anything else. Tagged `integration`, excluded from the default build (see below), and the one test in this project that needs Ollama reachable every time it runs. |
| [`PromptNormalizerVolatileValuesTest`](src/test/java/com/example/vcrdemo/PromptNormalizerVolatileValuesTest.java) | A `VcrPromptNormalizer` bean (`RegexPromptNormalizer.ISO_DATE`) collapsing two prompts that differ only by which calendar date they name into one committed fixture. |
| [`FixtureRedactorSecretsTest`](src/test/java/com/example/vcrdemo/FixtureRedactorSecretsTest.java) | A `VcrFixtureRedactor` bean keeping a customer id out of the committed fixture -- read the JSON in `llm-cache/redactor/` yourself -- while two different customer ids still produce two separate fixtures, proving redaction can never cause a cache collision. |
| [`AutoConfigPropertiesYamlTest`](src/test/java/com/example/vcrdemo/AutoConfigPropertiesYamlTest.java) | The "quick start" story: zero `@Bean`, zero manual advisor wiring, just `spring.ai.test.vcr.*` properties. Contrast with the two tests above, which each need one extra bean on top of this baseline. |
| [`ToolCallingRecordReplayTest`](src/test/java/com/example/vcrdemo/ToolCallingRecordReplayTest.java) | How to test a real `@Tool` method deterministically. `VcrScope.INSIDE_TOOL_LOOP` caches one fixture per model turn, so a two-turn tool-calling conversation (the model asks to call the tool, the real tool runs, its result goes back, the model answers) records two fixtures, not one -- and the real `@Tool` method keeps running on every replay, not just on the first live call. |
| [`StructuredOutputRecordReplayTest`](src/test/java/com/example/vcrdemo/StructuredOutputRecordReplayTest.java) | How to test a `ChatClient...call().entity(Class)` structured-output call deterministically. POJO conversion happens client-side after the (possibly replayed) response comes back, so a replay converts to the same object a live call already produced. Uses `spec.useProviderStructuredOutput()` -- Ollama's native, JSON-schema-constrained decoding -- which this project's small model follows far more reliably than the default text-instruction-based converter. |
| [`AssertionsShowcaseTest`](src/test/java/com/example/vcrdemo/AssertionsShowcaseTest.java) | spring-ai-test-tools's Assertions layer (`VcrAssertions.assertThat(...)`), used against the tool-calling and structured-output fixtures above -- read straight off disk via the library's own public `VcrTrackStore`/`VcrTrackMapper`, no new recording. Reads the tool-calling fixture's *first turn* specifically, because that turn's response is the model's still-pending tool call, before Spring AI's built-in tool loop resolves it -- see the test's own Javadoc, and `hasToolCall`'s Javadoc in the library, for why a normal `ChatClient.tools(...).call()`'s *final* response can't be asserted on the same way. |

## Running the excluded integration test

```bash
mvn test -Pintegration
```

Needs Ollama reachable at `http://localhost:11434` with `llama3.2:1b` pulled (see
`src/test/resources/application.yml`). Every other test needs none of that.

## How the fixtures were produced

1. Start a real Ollama with `llama3.2:1b` available locally.
2. Temporarily set the mode property in `ReplayOnlySealedCiTest.AKnownPromptStillReplays`,
   `PromptNormalizerVolatileValuesTest`, `FixtureRedactorSecretsTest`,
   `ToolCallingRecordReplayTest`, and `StructuredOutputRecordReplayTest` from
   `REPLAY_ONLY` to `RECORD_OR_REPLAY` (`RecordReplayBasicsTest` and
   `AutoConfigPropertiesYamlTest` already use `RECORD_OR_REPLAY` permanently -- no
   change needed for those).
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

To re-record after a real change (a prompt, a model, spring-ai-test-tools itself), delete
the relevant fixture file(s) and repeat from step 2.

## Requirements

Java 21 · Spring Boot 4.0+ · Spring AI 2.0+ -- matching spring-ai-test-tools itself.

## Licence

Apache-2.0
