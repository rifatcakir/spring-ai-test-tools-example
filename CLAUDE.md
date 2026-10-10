# CLAUDE.md — spring-ai-test-tools-example

Instructions for any agent working in this repository.

## What this is

A standalone consumer of
[`io.github.rifatcakir:spring-ai-test-tools`](https://github.com/rifatcakir/spring-ai-test-tools),
using it **only through its published Maven coordinates and public API**, exactly as an
unrelated project would. One test per feature, fixtures committed. It is the proof that what the
library's documentation shows actually works for a user. `README.md` lists what each test
demonstrates.

## State (2026-10-10)

- Library `0.2.0` (Maven Central), on **Spring AI 2.0.1 and Spring Boot 4.1.1**: newer than the
  library's own build (2.0.0 / 4.0.0), on purpose, to show it works on the newest releases.
- `mvn test`: 37 tests, green, no model and no network (the Cassette Server tests use Docker
  and are skipped without it). CI green on `main`.
- Cassette Server tests use the published image
  `ghcr.io/rifatcakir/spring-ai-test-tools-server:0.2.0` through Testcontainers:
  - `CassetteServerRemoteStoreTest`: one application, a `REPLAY_ONLY` namespace seeded from
    `src/test/resources/llm-cache/replay-only`, replay, a loud miss, the admin API.
  - `CassetteServerSharedEnvironmentTest`: separate application instances against one server,
    with a counting stand-in `ChatModel`, so recording works in CI and every scenario asserts
    model call counts. Sharing a recording, re-recording and re-sealing through the admin API,
    delete, export into files, an unreachable server.

## Build and test

```bash
mvn test                 # everything except @Tag("integration"); no Ollama needed
mvn test -Pintegration   # plus the three @Tag("integration") tests that reach a real model (Ollama, llama3.2:1b)
```

Pinned on purpose: surefire `3.5.4` (3.6.0 ignores `excludedGroups`), compiler `3.13.0`.
Testcontainers comes from the Spring Boot BOM (2.x: the JUnit artifact is
`testcontainers-junit-jupiter`).

## Rules

- **Only the public API and published versions.** No snapshot of the library, no reaching into
  its internals. If a test needs something the library does not offer, that is a finding for
  the library, not a workaround here.
- **Fixtures are committed and must replay on Linux CI.** A new fixture is recorded once against
  a real model (or a stand-in where the README says so), reviewed, and committed. `mvn test`
  must never reach a model.
- **Tests assert call counts**, not only answers: a test that only checks text passes even when
  every call goes to the model.
- When the library releases a new version, bump it here and run everything; when the server
  releases one, update the image tag in both Cassette Server tests.

## Working agreements with the maintainer

- Work on a branch, push, run CI (`gh workflow run ci.yml --ref <branch>`), fast-forward `main`
  once green. No pull request unless asked.
- The maintainer writes in Turkish; answer in Turkish, keep code, commits and docs in English.

Related: the library [`rifatcakir/spring-ai-test-tools`](https://github.com/rifatcakir/spring-ai-test-tools)
(its `docs/STATUS.md` is the overall status) and the server
[`rifatcakir/spring-ai-test-tools-server`](https://github.com/rifatcakir/spring-ai-test-tools-server).
