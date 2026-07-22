package com.example.vcrdemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.testtools.recorder.VcrPromptNormalizer;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How Spring AI's own {@code Evaluator} mechanism (not something spring-ai-test-tools
 * had to build) becomes deterministic for free: {@link RelevancyEvaluator} is built from
 * a {@code ChatClient.Builder} exactly like any other consumer would build one -- the
 * one already autowired here, already customized by spring-ai-test-tools's {@code
 * ChatClientBuilderCustomizer} because {@code spring.ai.test.vcr.enabled=true} is set for
 * this whole project (see {@code src/test/resources/application.yml}).
 *
 * <p>{@code RelevancyEvaluator.evaluate(...)} internally does nothing more than an
 * ordinary {@code chatClientBuilder.build().prompt().user(...).call().content()} --
 * confirmed by reading spring-ai-test-tools's own bytecode analysis in its
 * {@code docs/VISION.md}, then reproduced here against this project's own committed
 * fixture. No new spring-ai-test-tools code exists for this at all: this test is proof
 * that a capability already works, not a demonstration of a new library feature.
 *
 * <p><strong>Why this test needs its own {@link VcrPromptNormalizer} bean, unlike every
 * other test in this project:</strong> {@code RelevancyEvaluator}'s judge prompt is
 * rendered by Spring AI's own {@code PromptTemplate} (backed by StringTemplate/ST), and
 * that rendering embeds the *recording JVM's own* platform line separator -- {@code
 * \r\n} on Windows, {@code \n} on Linux/macOS -- into the rendered message text. This is
 * the same category of cross-platform bug spring-ai-test-tools itself already found and
 * fixed for tool schemas and {@code entity()} format instructions (see its own {@code
 * docs/STATUS.md} and {@code CURRENT_SCHEMA_VERSION "4"}), but it shows up in a place
 * that fix doesn't cover: an evaluator's rendered judge prompt arrives at the cache key
 * generator as ordinary {@code UserMessage} text, indistinguishable from a real user's
 * own authored prompt -- which spring-ai-test-tools deliberately does *not* normalize by
 * default, since a real user's prompt could contain a meaningful {@code \r\n} that
 * should legitimately bust the cache. The fix lives here, in this test's own
 * configuration, using the extension point spring-ai-test-tools already provides for
 * exactly this situation ({@link VcrPromptNormalizer}) -- not in the library itself,
 * since only the caller knows a given message is machine-rendered rather than
 * user-authored. Confirmed for real: this fixture, recorded on Windows without the
 * normalizer, missed on this project's own Linux CI with a genuine
 * {@code VcrCacheMissException} until the normalizer was added and the fixture
 * re-recorded.
 *
 * <p><strong>Read the fixture before assuming what it says:</strong> despite the
 * response plainly being supported by the context, {@code llama3.2:1b} answered "No."
 * to {@code RelevancyEvaluator}'s judge prompt on the recording that ended up committed
 * here -- several re-recording attempts produced "No.", "NO.", and once "YES", with no
 * apparent way to force a particular one. This is left as the honestly-recorded verdict
 * rather than fished for a "nicer" answer: the point of this test is that a replay is
 * *exactly* what was recorded, whatever the judge said, not that the judge's opinion is
 * correct. A small model's judge quality is a property of the model being judged, not
 * something spring-ai-test-tools controls or improves -- see its own
 * {@code docs/VISION.md} for the same caveat about Evaluator determinism versus judge
 * correctness.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/evaluator" })
class EvaluatorRecordReplayTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Test
	void relevancyEvaluatorJudgeCallReplaysExactlyWhatWasRecorded() throws IOException {
		Evaluator relevancyEvaluator = RelevancyEvaluator.builder().chatClientBuilder(this.chatClientBuilder).build();

		EvaluationRequest request = new EvaluationRequest("What is the capital of France?",
				List.of(new Document("Paris is the capital and most populous city of France.")),
				"The capital of France is Paris.");

		EvaluationResponse firstResult = relevancyEvaluator.evaluate(request);
		EvaluationResponse secondResult = relevancyEvaluator.evaluate(request);

		// REPLAY_ONLY against a committed fixture: this is a known, fixed verdict, not
		// "whatever the judge model happens to decide on this run" -- asserting the
		// exact value proves a real replay happened. The verdict itself is "false"
		// because that is what llama3.2:1b actually answered when this was recorded
		// (see the class Javadoc) -- not the "right" answer a human would give, but the
		// real one, which is the whole point: replay exactness, not judge correctness.
		assertThat(firstResult.isPass()).as("the committed fixture's exact recorded verdict").isFalse();
		assertThat(secondResult.isPass()).as("a replay must return exactly what was recorded")
			.isEqualTo(firstResult.isPass());
		assertThat(secondResult.getScore()).isEqualTo(firstResult.getScore());

		Path cacheDirectory = Path.of("src/test/resources/llm-cache/evaluator");
		try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
			assertThat(fixtures).as("exactly one fixture for this one distinct judge call").hasSize(1);
		}
	}

	@TestConfiguration
	static class NormalizerConfig {

		/**
		 * Collapses the recording JVM's own line separator in
		 * {@code RelevancyEvaluator}'s rendered judge prompt -- see the class Javadoc.
		 */
		@Bean
		VcrPromptNormalizer normalizeJudgePromptLineEndings() {
			return text -> text.replace("\r\n", "\n").replace("\r", "\n");
		}

	}

}
