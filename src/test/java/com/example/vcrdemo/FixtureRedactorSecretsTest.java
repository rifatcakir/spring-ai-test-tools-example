package com.example.vcrdemo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.testtools.recorder.VcrFixtureRedactor;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@link VcrFixtureRedactor} keeps a value out of the committed fixture without ever
 * letting it affect which fixture a request resolves to -- unlike a
 * {@code VcrPromptNormalizer}, which would merge every customer's request into one
 * shared cache entry. This test proves both halves of that: the customer id is not on
 * disk, and two different customer ids still produce two separate fixtures.
 *
 * <p>Read {@code src/test/resources/llm-cache/redactor/*.json} yourself after running
 * this: you'll see {@code [REDACTED]} where the raw customer id would otherwise have
 * been recorded, in a fixture that is otherwise a normal, readable JSON file.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/redactor" })
class FixtureRedactorSecretsTest {

	private static final Path CACHE_DIRECTORY = Path.of("src/test/resources/llm-cache/redactor");

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Test
	void customerIdIsRedactedOnDiskButStillDeterminesTheCacheEntry() throws IOException {
		ChatClient chatClient = this.chatClientBuilder.build();

		String firstResponse = chatClient.prompt()
			.user("The account for customer-12345 needs a status update. Reply with exactly one word: handled")
			.call()
			.content();
		String secondResponse = chatClient.prompt()
			.user("The account for customer-67890 needs a status update. Reply with exactly one word: handled")
			.call()
			.content();

		assertThat(firstResponse).isNotBlank();
		assertThat(secondResponse).isNotBlank();

		List<String> fixtureContents;
		try (Stream<Path> fixtures = Files.list(CACHE_DIRECTORY)) {
			fixtureContents = fixtures.map(FixtureRedactorSecretsTest::readQuietly).toList();
		}

		assertThat(fixtureContents)
			.as("two different customer ids must never collide into one fixture, even though both are redacted")
			.hasSize(2);
		assertThat(fixtureContents).allSatisfy(content -> assertThat(content).doesNotContain("customer-12345")
			.doesNotContain("customer-67890")
			.contains("[REDACTED]"));
	}

	private static String readQuietly(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@TestConfiguration
	static class RedactorConfig {

		@Bean
		VcrFixtureRedactor redactCustomerId() {
			// canonicalRequest is redacted too, not just request.messages(): it is a
			// separate top-level field that also embeds the raw message text (it's what
			// the hash was computed over), so leaving it untouched would still leak the
			// customer id right back into the committed fixture through a different field.
			return track -> new VcrTrack(track.schemaVersion(), track.hash(), track.recordedAt(),
					track.canonicalRequest().replaceAll("customer-\\d+", "[REDACTED]"),
					new VcrTrack.RequestSnapshot(track.request().model(), track.request().temperature(),
							track.request().topP(), track.request().topK(), track.request().maxTokens(),
							track.request().stopSequences(),
							track.request()
								.messages()
								.stream()
								.map(message -> new VcrTrack.MessageSnapshot(message.type(),
										message.text().replaceAll("customer-\\d+", "[REDACTED]"),
										message.toolCalls(), message.toolResponses()))
								.toList(),
							track.request().tools()),
					track.response());
		}

	}

}
