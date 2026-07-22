package com.example.vcrdemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core value proposition, demonstrated end to end: the first call to a real model
 * writes a fixture; every call after that -- in this run, and in every run after this
 * one, forever -- replays it instead.
 *
 * <p>Mode is {@code RECORD_OR_REPLAY}: the safe local-development default. If a fixture
 * is missing, it records one. Once one exists (as it does here, committed to this repo),
 * every future run just replays -- which is exactly why running {@code mvn test} for
 * this whole project needs no Docker, no Ollama, and no network at all once its
 * fixtures are checked in.
 *
 * <p>To prove that for yourself: stop Ollama entirely (or just don't start it -- a
 * fresh clone of this repo has no running model at all) and run {@code mvn test}. This
 * test still passes, because the two calls below never need to reach a real model
 * again after the fixture in {@code src/test/resources/llm-cache/basics/} was recorded.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=RECORD_OR_REPLAY",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/basics" })
class RecordReplayBasicsTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Test
	void secondIdenticalCallReplaysTheFirstResponseFromDisk() throws IOException {
		ChatClient chatClient = this.chatClientBuilder.build();
		String prompt = "Reply with exactly one word: acknowledged";

		String firstResponse = chatClient.prompt().user(prompt).call().content();
		String secondResponse = chatClient.prompt().user(prompt).call().content();

		// This fixture is already committed, so both calls above replay it -- "firstResponse"
		// is not a live answer either. The committed model reply is the literal text "No."
		// (the model didn't actually comply with the one-word instruction when this was
		// first recorded) -- asserting that exact value, not just "non-blank", proves this
		// really is a replay of the known fixture rather than some other text.
		assertThat(firstResponse).as("the committed fixture's exact recorded response").isEqualTo("No.");
		assertThat(secondResponse).as("a replay must return exactly what was recorded, not a fresh answer")
			.isEqualTo(firstResponse);

		Path cacheDirectory = Path.of("src/test/resources/llm-cache/basics");
		try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
			assertThat(fixtures).as("exactly one fixture should exist for this one distinct prompt").hasSize(1);
		}
	}

}
