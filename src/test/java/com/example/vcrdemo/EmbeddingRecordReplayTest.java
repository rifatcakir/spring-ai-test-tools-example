package com.example.vcrdemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How you test an {@code EmbeddingModel} call deterministically (R4): the exact same
 * record-once-replay-forever story {@link RecordReplayBasicsTest} shows for chat,
 * applied to {@code EmbeddingModel.embed(String)} instead of {@code ChatClient}.
 *
 * <p>{@code EmbeddingModel} has no advisor chain the way {@code ChatClient} does, so
 * interception happens differently under the hood -- a {@code BeanPostProcessor} wraps
 * the autoconfigured {@code OllamaEmbeddingModel} bean directly, gated by its own
 * {@code spring.ai.test.vcr.embedding.enabled} flag, independent of the chat
 * {@code spring.ai.test.vcr.enabled} flag used elsewhere in this project. From this
 * test's own code, the difference is invisible: {@code @Autowired EmbeddingModel} is
 * exactly what any consumer would already inject.
 *
 * <p>Two things worth knowing about the committed fixture in
 * {@code src/test/resources/llm-cache-embedding/}: the vector is {@code llama3.2:1b}'s
 * real output (2048 dimensions -- confirmed by a real call before this fixture was
 * recorded, not assumed), and the fixture file itself is the one place in this whole
 * project that is not meaningfully human-reviewable in a PR diff -- see
 * {@code docs/R4-EMBEDDING-INTERCEPTION.md} in the main library's repo for why storing
 * the full vector (not a hash of it) is correct anyway: a hash could never actually
 * replay a usable vector, which is the entire point of this fixture existing.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.embedding.mode=REPLAY_ONLY",
		"spring.ai.test.vcr.embedding.cache-directory=src/test/resources/llm-cache-embedding" })
class EmbeddingRecordReplayTest {

	@Autowired
	private EmbeddingModel embeddingModel;

	@Test
	void secondIdenticalCallReplaysTheExactVectorFromDisk() throws IOException {
		String input = "Istanbul is the largest city in Turkey by population.";

		float[] firstVector = this.embeddingModel.embed(input);
		float[] secondVector = this.embeddingModel.embed(input);

		// A replay must return the exact recorded vector, not merely one of the same
		// length -- this is the one assertion type that matters most for R4, since a
		// future semantic/cosine-similarity assertion (A2) would silently compute
		// against the wrong numbers if replay were only "close enough".
		assertThat(firstVector).as("llama3.2:1b's real embedding dimensionality, confirmed before recording")
			.hasSize(2048);
		assertThat(secondVector).as("a replay must return exactly what was recorded, not a fresh vector")
			.isEqualTo(firstVector);

		Path cacheDirectory = Path.of("src/test/resources/llm-cache-embedding");
		try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
			assertThat(fixtures).as("exactly one fixture should exist for this one distinct input").hasSize(1);
		}
	}

}
