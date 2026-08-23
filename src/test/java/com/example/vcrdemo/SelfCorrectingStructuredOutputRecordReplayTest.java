package com.example.vcrdemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How you test Spring AI 2.0's new self-correcting structured output
 * ({@code .entity(Class, spec -> spec.validateSchema())}) deterministically: exactly the
 * same pattern {@link StructuredOutputRecordReplayTest} already shows for plain
 * {@code entity()}, because from spring-ai-test-tools's point of view nothing new is
 * happening.
 *
 * <p><strong>Why that's true, not just hoped:</strong> {@code validateSchema()} attaches a
 * {@code StructuredOutputValidationAdvisor} to the call. It's a <em>recursive</em> advisor
 * -- on a malformed response it appends the validation error to the prompt and calls the
 * model again, up to {@code maxRepeatAttempts} times (default 3) -- but its default order
 * ({@code LOWEST_PRECEDENCE - 2000}) sits far below where spring-ai-test-tools's advisor
 * runs (near {@code HIGHEST_PRECEDENCE}). So VCR only ever sees <em>one</em> call go
 * through it: the whole self-correction loop, real retries included, happens entirely
 * <em>inside</em> the single {@code nextCall} VCR makes on a miss, and gets recorded as one
 * fixture -- the loop's final, validated answer. A replay returns that answer directly and
 * never re-enters the loop at all. See the main library's own
 * {@code docs/R5-RECURSIVE-ADVISOR-SCOPE-PRD.md} for the full analysis, confirmed against
 * a real model with a forced multi-attempt loop in
 * {@code OllamaStructuredOutputValidationEndToEndTests} there.
 *
 * <p>Deliberately uses the <em>default</em>, text-instruction-based converter here (not
 * {@code useProviderStructuredOutput()}, which {@link StructuredOutputRecordReplayTest}
 * uses) -- {@code validateSchema()}'s entire reason to exist is catching and correcting the
 * malformed/incomplete JSON {@code llama3.2:1b} sometimes produces under that less reliable
 * path (see this project's README, "How the fixtures were produced", for prior examples of
 * exactly that quirk); pairing it with native structured output would leave nothing for it
 * to actually correct.
 *
 * <p><strong>Honest note on what actually got recorded:</strong> this specific fixture's
 * real model call happened to produce valid JSON on the first attempt -- no retry was
 * needed, so {@code maxRepeatAttempts}' loop never actually looped here. That's a real,
 * legitimate outcome (this is a genuine two-field-plus-array schema, not a trivial one, and
 * {@code llama3.2:1b} got it right first try this time), not a cherry-picked "clean" run --
 * see the class Javadoc above for why the one-fixture-per-loop guarantee holds either way.
 * For a fixture that proves the *actual* multi-attempt retry mechanism working end to end
 * -- a forced validation failure, a real second HTTP call with the augmented
 * error-feedback prompt, still one fixture -- see the main library's own
 * {@code OllamaStructuredOutputValidationEndToEndTests}, which deliberately engineers a
 * guaranteed-to-fail schema (a hidden {@code const} the model is never shown) specifically
 * because a real small model's natural failure rate can't be relied on for a repeatable
 * demonstration.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/self-correcting-structured-output" })
class SelfCorrectingStructuredOutputRecordReplayTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	record TravelItinerary(String destination, List<String> activities, Boolean visaRequired) {
	}

	@Test
	void entityCallWithValidateSchemaRecordsTheWholeSelfCorrectionLoopAsOneFixture() throws IOException {
		ChatClient chatClient = this.chatClientBuilder.build();
		String prompt = "Invent a short, fictional weekend trip itinerary. The destination must be a real city, "
				+ "activities must be a list of at least two short strings, and visaRequired must be true or false.";

		TravelItinerary first = chatClient.prompt().user(prompt).call().entity(TravelItinerary.class,
				spec -> spec.validateSchema());
		TravelItinerary second = chatClient.prompt().user(prompt).call().entity(TravelItinerary.class,
				spec -> spec.validateSchema());

		// REPLAY_ONLY against a committed fixture: known, exact recorded values, not
		// "whatever the model happens to invent" -- proves entity() actually parsed the
		// recorded JSON, not just that some non-null object came back.
		assertThat(first.destination()).as("the committed fixture's exact recorded destination")
			.isEqualTo("Paris, France");
		assertThat(first.activities()).as("the committed fixture's exact recorded activities")
			.containsExactly("Visit the iconic Eiffel Tower for panoramic views of Paris");
		assertThat(first.visaRequired()).as("the committed fixture's exact recorded visaRequired flag").isTrue();
		assertThat(second).as("a replay must convert to the same object a live call already produced")
			.isEqualTo(first);

		Path cacheDirectory = Path.of("src/test/resources/llm-cache/self-correcting-structured-output");
		try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
			// One fixture regardless of how many real self-correction attempts the
			// validation advisor made while recording -- the whole loop is invisible to
			// spring-ai-test-tools's own advisor, which sits above it. See the class
			// Javadoc.
			assertThat(fixtures).as("one fixture wraps the entire validateSchema() self-correction loop, "
					+ "no matter how many real attempts it took").hasSize(1);
		}
	}

}
