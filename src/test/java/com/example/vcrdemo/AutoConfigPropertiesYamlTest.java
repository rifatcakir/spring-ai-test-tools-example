package com.example.vcrdemo;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "Quick start" story from spring-ai-test-vcr's own README, proven rather than just
 * described: this test class defines no {@code @Bean}, no {@code @TestConfiguration},
 * no manual advisor wiring of any kind. {@code spring.ai.test.vcr.enabled=true} in
 * {@code application.yml} plus the two properties below are the entire integration.
 * {@code ChatClient.Builder} arrives already wrapped with the caching advisor because
 * spring-ai-test-vcr's auto-configuration attached it via
 * {@code ChatClientBuilderCustomizer} -- no production code, and no test code beyond
 * this, ever needs to know the cache exists.
 *
 * <p>Contrast with {@link PromptNormalizerVolatileValuesTest} and
 * {@link FixtureRedactorSecretsTest}, which -- deliberately -- do need a
 * {@code @TestConfiguration} bean each, because normalizing or redacting is opting into
 * extra behaviour. This test is the baseline that extra behaviour is optional on top of.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=RECORD_OR_REPLAY",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/yaml-config" })
class AutoConfigPropertiesYamlTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Test
	void chatClientIsCachedWithZeroManualWiring() {
		ChatClient chatClient = this.chatClientBuilder.build();
		String prompt = "Reply with exactly one word: configured";

		String firstResponse = chatClient.prompt().user(prompt).call().content();
		String secondResponse = chatClient.prompt().user(prompt).call().content();

		// This fixture is already committed, so both calls above replay it -- the exact
		// recorded text ("No.", since this small model didn't actually comply with the
		// one-word instruction when it was first recorded) is known, not just "some
		// non-empty string". Asserting it directly, rather than only comparing the two
		// calls to each other, rules out both calls trivially agreeing on an empty string.
		assertThat(firstResponse).as("the committed fixture's exact recorded response").isEqualTo("No.");
		assertThat(secondResponse).as("a replay must return exactly what was recorded").isEqualTo(firstResponse);
	}

}
