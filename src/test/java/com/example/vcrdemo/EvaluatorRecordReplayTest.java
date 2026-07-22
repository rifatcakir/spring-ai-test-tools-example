package com.example.vcrdemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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

}
