# spring-ai-test-vcr-example

A standalone consumer of [spring-ai-test-vcr](https://github.com/rifatcakir/spring-ai-test-vcr),
exercising it purely through its public API and its published Maven coordinates -- exactly
as an unrelated third-party project would. This exists as spring-ai-test-vcr's final
pre-publish verification gate: if something in this repo breaks, the library's public API
surface broke for a real consumer, not just for its own internal test suite.

## `mvn test` needs no Docker, no Ollama, and no network

Every fixture this project needs is already committed under
`src/test/resources/llm-cache/`. Clone this repo, run `mvn test`, and it passes cold --
try it with Ollama stopped entirely (`docker stop ollama` or however you're running it) to
see for yourself. One test is the deliberate exception: see
[`VcrAnnotationEscapeHatchTest`](#what-each-test-demonstrates) below.

## Setup

spring-ai-test-vcr is not yet published to Maven Central. Until it is, install it into
your local repository first:

```bash
cd ../spring-ai-test-vcr   # or wherever you cloned it
mvn clean install          # not -Prelease -- no signing needed just to consume it locally
```

This project's `pom.xml` then depends on it as an ordinary, fixed-version dependency:

```xml
<dependency>
    <groupId>io.github.rifatcakir</groupId>
    <artifactId>spring-ai-test-vcr</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

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

## Running the excluded integration test

```bash
mvn test -Pintegration
```

Needs Ollama reachable at `http://localhost:11434` with `llama3.2:1b` pulled (see
`src/test/resources/application.yml`). Every other test needs none of that.

## How the fixtures were produced

1. Start a real Ollama with `llama3.2:1b` available locally.
2. Temporarily set the mode property in `ReplayOnlySealedCiTest.AKnownPromptStillReplays`,
   `PromptNormalizerVolatileValuesTest`, `FixtureRedactorSecretsTest`, and
   `ToolCallingRecordReplayTest` from `REPLAY_ONLY` to `RECORD_OR_REPLAY`
   (`RecordReplayBasicsTest` and `AutoConfigPropertiesYamlTest` already use
   `RECORD_OR_REPLAY` permanently -- no change needed for those).
3. Run `mvn test` once. Every fixture gets written under
   `src/test/resources/llm-cache/<test-name>/`. `ToolCallingRecordReplayTest` writes
   *two* fixtures for its one tool call -- `VcrScope.INSIDE_TOOL_LOOP` caches one per
   model turn, not one for the whole round trip.
4. **Read the generated JSON before committing it** -- exactly as spring-ai-test-vcr's own
   `CLAUDE.md` insists on for its own fixtures. This is where a redaction bug was actually
   caught while building this project: the first attempt redacted `request.messages()`
   but forgot that `canonicalRequest` is a *separate* field that also embeds the raw
   message text, so the customer id was still leaking through it. Reading the fixture,
   not just trusting the assertions, is what caught that. For the tool-calling fixtures
   specifically, check that the tool call's function name and arguments are exactly what
   you expect (`getOrderStatus` / `{"orderId":"ORD-4471"}`) and that the tool's response
   made it into the second turn's fixture.
5. Flip the four properties in step 2 back to `REPLAY_ONLY`, then run `mvn test` again
   with Ollama stopped to confirm nothing regressed.
6. Commit the fixtures.

To re-record after a real change (a prompt, a model, spring-ai-test-vcr itself), delete
the relevant fixture file(s) and repeat from step 2.

## Requirements

Java 21 · Spring Boot 4.0+ · Spring AI 2.0+ -- matching spring-ai-test-vcr itself.

## Licence

Apache-2.0
