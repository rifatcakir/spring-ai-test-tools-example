package com.example.vcrdemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How you test {@code ChatClient...stream()} deterministically (R3): the same
 * record-once-replay-forever story {@link RecordReplayBasicsTest} tells for
 * {@code .call()}, applied to a streamed {@code Flux<String>} instead.
 *
 * <p>The fixture in {@code src/test/resources/llm-cache-stream/} stores every chunk the
 * live stream produced, in order -- not just the concatenated final answer. That
 * distinction is what this test actually asserts on: {@code containsExactly} against the
 * full chunk list, not merely that the joined text matches. A fixture that only stored
 * the aggregate could never prove a replay reproduces the same chunk boundaries a live
 * call would have, and chunk-boundary fidelity is the entire reason {@code
 * VcrStreamTrack} stores raw chunks in the first place -- see
 * {@code docs/R3-STREAMING-PRD.md} in the main library's repo.
 *
 * <p>Mode is {@code RECORD_OR_REPLAY}, the same safe local-development default {@link
 * RecordReplayBasicsTest} uses: this fixture is already committed, so every run just
 * replays -- no Docker, no Ollama, no network needed once it's checked in. To prove that
 * for yourself: stop Ollama entirely and run {@code mvn test} again.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=RECORD_OR_REPLAY",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache-stream" })
class StreamingRecordReplayTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Test
	void secondIdenticalStreamReplaysTheExactChunkSequenceFromDisk() throws IOException {
		ChatClient chatClient = this.chatClientBuilder.build();
		String prompt = "Reply with exactly one word: acknowledged";

		List<String> firstChunks = chatClient.prompt().user(prompt).stream().content().collectList().block();
		List<String> secondChunks = chatClient.prompt().user(prompt).stream().content().collectList().block();

		// This fixture is already committed, so both streams above replay it -- neither
		// is a live answer. Asserting the exact recorded chunk sequence, not just the
		// joined text, proves this really is a chunk-for-chunk replay of the known
		// fixture rather than a single-chunk fake standing in for one.
		assertThat(firstChunks).as("the committed fixture's exact recorded chunk sequence")
			.containsExactly("Yes", ".");
		assertThat(secondChunks).as("a replay must reproduce the exact chunk sequence, in the same order")
			.containsExactlyElementsOf(firstChunks);

		Path cacheDirectory = Path.of("src/test/resources/llm-cache-stream");
		try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
			assertThat(fixtures).as("exactly one fixture should exist for this one distinct prompt").hasSize(1);
		}
	}

}
