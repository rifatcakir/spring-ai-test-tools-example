package com.example.vcrdemo;

import io.github.rifatcakir.springai.vcr.RegexPromptNormalizer;
import io.github.rifatcakir.springai.vcr.VcrPromptNormalizer;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without help, a prompt containing today's date would hash differently every single
 * day, missing forever and growing the fixture directory without bound. Registering
 * {@link RegexPromptNormalizer#ISO_DATE} as a {@link VcrPromptNormalizer} bean fixes
 * that: the date is replaced with a stable placeholder before the hash is computed, so
 * two prompts that differ only by which calendar date they name still resolve to the
 * one committed fixture.
 *
 * <p>The fixture in {@code src/test/resources/llm-cache/normalizer/} was recorded once,
 * for one specific date. This test deliberately uses two different literal dates -- one
 * of them almost certainly not the date it was originally recorded on -- to prove the
 * normalizer, not luck, is what makes this replay regardless of which real date it
 * happens to run on in the future.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/normalizer" })
class PromptNormalizerVolatileValuesTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Test
	void twoPromptsDifferingOnlyByDateShareOneFixture() {
		ChatClient chatClient = this.chatClientBuilder.build();

		String responseForOneDate = chatClient.prompt()
			.user("Today is 2026-01-15. Reply with exactly one word: noted")
			.call()
			.content();
		String responseForADifferentDate = chatClient.prompt()
			.user("Today is 2031-11-02. Reply with exactly one word: noted")
			.call()
			.content();

		assertThat(responseForOneDate).isNotBlank();
		assertThat(responseForADifferentDate)
			.as("both dates normalize to the same placeholder, so both must hit the same fixture")
			.isEqualTo(responseForOneDate);
	}

	@TestConfiguration
	static class NormalizerConfig {

		@Bean
		VcrPromptNormalizer ignoreDates() {
			return RegexPromptNormalizer.ISO_DATE;
		}

	}

}
