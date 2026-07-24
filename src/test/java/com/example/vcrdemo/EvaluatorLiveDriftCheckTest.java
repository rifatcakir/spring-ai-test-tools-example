package com.example.vcrdemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.VcrPromptNormalizer;
import io.github.rifatcakir.springai.testtools.recorder.junit.Vcr;
import org.junit.jupiter.api.Tag;
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
 * E2's actual point, demonstrated against this project's own committed fixture: the
 * exact same {@link RelevancyEvaluator}, built from the exact same
 * {@code ChatClient.Builder}, evaluating the exact same {@link EvaluationRequest} that
 * {@link EvaluatorRecordReplayTest} already replays deterministically from disk — but
 * this test opts out of that seal for itself with {@code @Vcr(mode =
 * VcrMode.BYPASS)}, spring-ai-test-tools's own existing escape hatch (already
 * demonstrated for a plain chat call by {@link VcrAnnotationEscapeHatchTest}), reaching
 * the real model instead. No new spring-ai-test-tools mechanism: which mode is active
 * is purely a property of the shared advisor's configuration, and {@code @Vcr} already
 * lets one test override it without weakening the seal for anything else.
 *
 * <p>This is the "live drift/quality check" half of the two-mode story
 * spring-ai-test-tools's own {@code docs/E2-EVALUATION-MODES-PRD.md} documents: a
 * deliberate, separate way to ask "is the model's judgment on this exact case still
 * what it was when this fixture was committed" — never the default CI path (see
 * {@code pom.xml}'s {@code excludedGroups}, which is what actually keeps this out of
 * the default {@code mvn test} run and this project's CI).
 *
 * <p>Tagged {@code integration}, needs a real, reachable Ollama every time it runs. Run
 * it explicitly with {@code mvn test -Pintegration}. Deliberately does not assert on
 * the verdict itself -- a live call to a small model can vary run to run (see
 * {@code EvaluatorRecordReplayTest}'s own Javadoc) -- only that the committed fixture
 * directory is untouched: {@code BYPASS} never reads *or* writes a fixture, unlike
 * {@code RECORD_ALWAYS}, which would overwrite it.
 */
@Tag("integration")
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/evaluator" })
class EvaluatorLiveDriftCheckTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Test
	@Vcr(mode = VcrMode.BYPASS)
	void bypassReachesTheRealModelDespiteTheCommittedFixtureExisting() throws IOException {
		Evaluator relevancyEvaluator = RelevancyEvaluator.builder().chatClientBuilder(this.chatClientBuilder).build();

		// The exact same EvaluationRequest EvaluatorRecordReplayTest already replays
		// deterministically -- same hash, same fixture sitting on disk right now.
		EvaluationRequest request = new EvaluationRequest("What is the capital of France?",
				List.of(new Document("Paris is the capital and most populous city of France.")),
				"The capital of France is Paris.");

		EvaluationResponse liveResult = relevancyEvaluator.evaluate(request);

		assertThat(liveResult).as("a live BYPASS call must still return a real verdict, not merely avoid throwing")
			.isNotNull();

		Path cacheDirectory = Path.of("src/test/resources/llm-cache/evaluator");
		try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
			assertThat(fixtures)
				.as("BYPASS neither reads nor writes a fixture -- the committed one must be exactly the one already there")
				.hasSize(1);
		}
	}

	@TestConfiguration
	static class NormalizerConfig {

		/**
		 * Same normalizer {@link EvaluatorRecordReplayTest} registers, for the same
		 * reason -- irrelevant to a {@code BYPASS} call itself (which never hashes
		 * anything), but needed so this test's Spring context matches the one the
		 * committed fixture was recorded against.
		 */
		@Bean
		VcrPromptNormalizer normalizeJudgePromptLineEndings() {
			return text -> text.replace("\r\n", "\n").replace("\r", "\n");
		}

	}

}
