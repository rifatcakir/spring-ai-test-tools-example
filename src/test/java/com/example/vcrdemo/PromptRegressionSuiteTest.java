package com.example.vcrdemo;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The story design rule #5 in the main library's {@code CLAUDE.md} tells but that no
 * other test in this project demonstrates end to end: <strong>a committed fixture is a
 * prompt regression check</strong>. Three unrelated prompts below are this project's
 * small "golden suite" -- each one's exact recorded answer is committed, reviewed, and
 * pinned, the same way a snapshot test pins a golden output. If a teammate reshapes any
 * one of these prompts, even by a single word, CI does not silently keep passing with a
 * quietly different answer -- it fails loudly with {@link VcrCacheMissException}, because
 * the changed prompt no longer matches its committed fixture's hash. {@link
 * #reshapingAGoldenPromptByOneWordFailsLoudInsteadOfDriftingSilently()} proves that last
 * part concretely rather than only asserting it in prose: it takes one of the three golden
 * prompts below, changes a single word, and shows the result is a loud miss, never a
 * silently reused "close enough" answer -- design rule #1's whole reason to exist,
 * demonstrated against this project's own committed fixtures instead of only described.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/prompt-regression" })
class PromptRegressionSuiteTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Test
	void goldenPromptOneStillAnswersExactlyAsRecorded() {
		ChatClient chatClient = this.chatClientBuilder.build();

		String answer = chatClient.prompt().user("Reply with exactly one word: hello").call().content();

		// This small model didn't actually comply with the one-word instruction when this
		// was first recorded -- asserting the exact value anyway, same as this project's
		// other tests, proves this is a real replay rather than an assumption.
		assertThat(answer).as("the committed fixture's exact recorded answer").isEqualTo("Good");
	}

	@Test
	void goldenPromptTwoStillAnswersExactlyAsRecorded() {
		ChatClient chatClient = this.chatClientBuilder.build();

		String answer = chatClient.prompt().user("What is 7 multiplied by 6? Reply with only the number.").call()
			.content();

		assertThat(answer).as("the committed fixture's exact recorded answer").isEqualTo("42");
	}

	@Test
	void goldenPromptThreeStillAnswersExactlyAsRecorded() {
		ChatClient chatClient = this.chatClientBuilder.build();

		String answer = chatClient.prompt().user("Is the sky blue? Reply with only yes or no.").call().content();

		assertThat(answer).as("the committed fixture's exact recorded answer").isEqualTo("Yes.");
	}

	/**
	 * "number" -> "numeral": the same golden question from
	 * {@link #goldenPromptTwoStillAnswersExactlyAsRecorded()}, reshaped by exactly one
	 * word -- exactly the kind of edit a teammate makes without realizing it changes
	 * anything. It has, structurally: this is a different SHA-256 hash, so there is no
	 * fixture for it, sealed {@code REPLAY_ONLY} refuses to reach a real model to make one
	 * up, and the failure names the drifted text directly so whoever sees it in CI knows
	 * exactly what changed and can decide whether to re-record deliberately.
	 */
	@Test
	void reshapingAGoldenPromptByOneWordFailsLoudInsteadOfDriftingSilently() {
		ChatClient chatClient = this.chatClientBuilder.build();

		assertThatExceptionOfType(VcrCacheMissException.class)
			.isThrownBy(() -> chatClient.prompt().user("What is 7 multiplied by 6? Reply with only the numeral.")
				.call()
				.content())
			.satisfies(ex -> assertThat(ex.getCanonicalRequest())
				.as("the exception shows the exact drifted request that missed, not just 'something changed'")
				.contains("What is 7 multiplied by 6? Reply with only the numeral."));
	}

}
