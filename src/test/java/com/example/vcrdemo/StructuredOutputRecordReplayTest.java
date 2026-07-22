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
 * How you test a Spring AI structured-output ({@code entity()}) call deterministically:
 * the exact {@code ChatClient...call().entity(Class)} pattern any consumer already uses,
 * recorded once and replayed identically thereafter.
 *
 * <p>POJO conversion happens entirely client-side, after the (possibly replayed) response
 * comes back from the advisor chain -- so a replay converts to the same object a live call
 * already produced, with no extra configuration on top of what {@link RecordReplayBasicsTest}
 * already shows for a plain text call.
 *
 * <p>Uses {@code spec.useProviderStructuredOutput()} -- Ollama's native, JSON-schema-
 * constrained decoding -- rather than the default text-instruction-based converter.
 * {@code llama3.2:1b} (this project's model throughout, chosen for size) reliably echoed
 * the schema itself back instead of filling in values under the default text-instruction
 * prompt; native structured output constrains generation at the token level instead of
 * relying on the model to follow written instructions, which this small a model handles
 * far more reliably. Either path is cached and replayed the same way -- see the main
 * library's README for how the two differ.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/structured-output" })
class StructuredOutputRecordReplayTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	record ShippingEstimate(String carrier, Integer estimatedDays) {
	}

	@Test
	void entityCallRecordsOnceAndReplaysTheSamePojo() throws IOException {
		ChatClient chatClient = this.chatClientBuilder.build();
		String prompt = "Invent a fictional shipping estimate for a package from Istanbul to Berlin. The estimated "
				+ "number of days must be a small whole number between 1 and 14.";

		ShippingEstimate first = chatClient.prompt()
			.user(prompt)
			.call()
			.entity(ShippingEstimate.class, spec -> spec.useProviderStructuredOutput());
		ShippingEstimate second = chatClient.prompt()
			.user(prompt)
			.call()
			.entity(ShippingEstimate.class, spec -> spec.useProviderStructuredOutput());

		// REPLAY_ONLY against a committed fixture: the DTO's fields are not "whatever the
		// model happened to invent" -- they're the exact, known values on disk. Asserting
		// them proves entity() actually parsed the recorded JSON into the DTO, not just
		// that some non-null object came back.
		assertThat(first.carrier()).as("the committed fixture's exact recorded carrier").isEqualTo("Turkish Airlines");
		assertThat(first.estimatedDays()).as("the committed fixture's exact recorded estimate").isEqualTo(9);
		assertThat(second).as("a replay must convert to the same object a live call already produced")
			.isEqualTo(first);

		Path cacheDirectory = Path.of("src/test/resources/llm-cache/structured-output");
		try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
			assertThat(fixtures).as("exactly one fixture for this one distinct entity() call").hasSize(1);
		}
	}

}
