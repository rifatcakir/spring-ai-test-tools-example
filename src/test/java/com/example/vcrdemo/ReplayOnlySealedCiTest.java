package com.example.vcrdemo;

import io.github.rifatcakir.springai.vcr.VcrCacheMissException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@code REPLAY_ONLY} is what CI actually runs with -- the mode with no escape hatch for
 * a network call. Two behaviours matter, so this is two nested contexts rather than one:
 * a fixture that exists still replays completely normally, and a fixture that does not
 * exist fails loudly and immediately, never touching a real model.
 */
class ReplayOnlySealedCiTest {

	/**
	 * The fixture this relies on was recorded once, then this file's mode was flipped
	 * from {@code RECORD_OR_REPLAY} to {@code REPLAY_ONLY} afterward -- see this
	 * project's README for the exact steps. From that point on, this passes with no
	 * model reachable at all.
	 */
	@Nested
	@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY",
			"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/replay-only" })
	class AKnownPromptStillReplays {

		@Autowired
		private ChatClient.Builder chatClientBuilder;

		@Test
		void hitsTheCommittedFixtureUnderTheSealedMode() {
			ChatClient chatClient = this.chatClientBuilder.build();

			String response = chatClient.prompt().user("Reply with exactly one word: sealed").call().content();

			assertThat(response).isNotBlank();
		}

	}

	/**
	 * No fixture for this prompt has ever existed, and never will -- that is the point.
	 * This nested class needs no Ollama, no Docker, and no network to pass, in this run
	 * or any other: {@code REPLAY_ONLY} refuses to call the real model on a miss, so the
	 * failure happens before anything reaches out.
	 */
	@Nested
	@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY",
			"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/replay-only" })
	class AnUnrecordedPromptFailsLoud {

		@Autowired
		private ChatClient.Builder chatClientBuilder;

		@Test
		void refusesToReachTheRealModelOnAMiss() {
			ChatClient chatClient = this.chatClientBuilder.build();

			assertThatExceptionOfType(VcrCacheMissException.class).isThrownBy(() -> chatClient.prompt()
				.user("This exact sentence has deliberately never been recorded as a fixture.")
				.call()
				.content())
				.satisfies(ex -> assertThat(ex.getMessage()).contains("REPLAY_ONLY"));
		}

	}

}
