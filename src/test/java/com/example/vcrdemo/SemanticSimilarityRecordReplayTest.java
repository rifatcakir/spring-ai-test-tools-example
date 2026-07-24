package com.example.vcrdemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.testtools.assertions.VcrAssertions;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How you test "is this answer close enough in meaning" deterministically (A2):
 * {@code VcrAssertions.assertThat(response).usingEmbeddingModel(embeddingModel)
 * .isSemanticallySimilarTo(expected, threshold)}. The {@code embeddingModel} autowired
 * here is exactly the same {@code EmbeddingModel} bean {@link EmbeddingRecordReplayTest}
 * (R4) already shows -- this project's {@code spring.ai.test.vcr.embedding.enabled=true}
 * baseline means it is already Recorder-backed before this test ever sees it, so both
 * embedding calls this assertion makes (the response text's and the expected text's)
 * replay from disk exactly like any other embedding call in this project.
 *
 * <p><strong>The threshold here is explicit ({@code 0.85}), not spring-ai-test-tools's
 * own {@code 0.7} default</strong> -- not a stylistic choice, an empirical finding from
 * the main library's own end-to-end test (see its {@code
 * docs/A2-SEMANTIC-ASSERTIONS-PRD.md} section 5 and {@code SemanticSimilarityEndToEndTests}):
 * {@code llama3.2:1b}'s embeddings (extracted from an LLM's hidden states, not a model
 * purpose-trained for embedding separation) compress real paraphrase and unrelated-topic
 * similarity into a narrow, uniformly-high range, so the library's own conservative
 * default does not reliably separate them for this specific model. A dedicated
 * embedding model would likely need a different threshold entirely -- the whole reason
 * the explicit-threshold overload exists rather than trusting one number for every model.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.embedding.mode=REPLAY_ONLY",
		"spring.ai.test.vcr.embedding.cache-directory=src/test/resources/llm-cache-embedding-semantic-similarity" })
class SemanticSimilarityRecordReplayTest {

	@Autowired
	private EmbeddingModel embeddingModel;

	@Test
	void aGenuineParaphrasePassesAtAThresholdCalibratedForThisModel() throws IOException {
		ChatResponse response = ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage("The capital of France is Paris."))))
			.build();

		VcrAssertions.assertThat(response)
			.usingEmbeddingModel(this.embeddingModel)
			.isSemanticallySimilarTo("Paris is the capital city of France.", 0.85);

		Path cacheDirectory = Path.of("src/test/resources/llm-cache-embedding-semantic-similarity");
		try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
			// One fixture per distinct embedded text -- the response text and the
			// expected text are two separate embedding calls, each its own fixture.
			assertThat(fixtures).as("one fixture for the response text, one for the expected text").hasSize(2);
		}
	}

}
