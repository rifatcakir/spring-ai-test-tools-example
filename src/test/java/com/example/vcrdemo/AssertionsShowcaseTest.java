package com.example.vcrdemo;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.rifatcakir.springai.testtools.assertions.VcrAssertions;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;

import org.springframework.ai.chat.model.ChatResponse;

import tools.jackson.databind.node.JsonNodeType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Showcases spring-ai-test-tools's Assertions layer (A1) — {@code
 * io.github.rifatcakir.springai.testtools.assertions.VcrAssertions} — against fixtures
 * this project already recorded for {@link ToolCallingRecordReplayTest} and
 * {@link StructuredOutputRecordReplayTest}. Deliberately reads the fixtures straight off
 * disk via the library's own public {@link VcrTrackStore}/{@link VcrTrackMapper} rather
 * than recording anything new: this is what a fixture already committed to a PR lets you
 * check without any model, Ollama, or Docker involved.
 *
 * <p><strong>Why this reads the tool-calling fixture's <em>first</em> turn, not a live
 * {@code ChatClient} call:</strong> {@link ToolCallingRecordReplayTest} already explains
 * that {@code VcrScope.INSIDE_TOOL_LOOP} records one fixture per model turn. That first
 * turn's {@link ChatResponse} <em>is</em> the model's tool-call request, still pending —
 * Spring AI's built-in {@code ToolCallingAdvisor} hasn't executed it yet at that point.
 * That is exactly the shape {@code VcrAssertions#hasToolCall} is designed to check (see
 * its own Javadoc's scope-limitation note, and {@code docs/A1-ASSERTIONS-PRD.md} section
 * 7.1 in the main library's repo for the full reasoning): a normal
 * {@code chatClient.prompt()...tools(...).call()}'s <em>final</em> response has already
 * had its tool call resolved and consumed by the time the caller sees it, so asserting
 * {@code hasToolCall(...)} against that final response would find nothing — which is
 * exactly why {@link ToolCallingRecordReplayTest} itself reads raw fixture files instead
 * of asserting on a response object for that check today.
 */
class AssertionsShowcaseTest {

	@Test
	void hasToolCallAssertsOnTheModelsStillPendingToolCallRequest() {
		VcrTrackStore store = new VcrTrackStore(Path.of("src/test/resources/llm-cache/tool-calling"));
		Optional<VcrTrack> firstTurn = store
			.read("3a3c5135cf3270a5545e8aa285fc2026c2b852af7abc108b2e6d4e7177ad430d");
		assertThat(firstTurn).as("the committed first-turn tool-calling fixture must still be on disk").isPresent();

		ChatResponse firstTurnResponse = new VcrTrackMapper().toChatResponse(firstTurn.get());

		VcrAssertions.assertThat(firstTurnResponse)
			.hasToolCall("getOrderStatus", args -> assertThat(args).containsEntry("orderId", "ORD-4471"))
			.hasFinishReason("stop");
	}

	@Test
	void hasJsonFieldAssertsOnAStructuredOutputResponseWithoutConvertingItToAPojoFirst() {
		VcrTrackStore store = new VcrTrackStore(Path.of("src/test/resources/llm-cache/structured-output"));
		Optional<VcrTrack> track = store
			.read("b27b4fbb47a1b84d7b2b25fb386668a5315332ee13a0ce8c8cc4734682b04cab");
		assertThat(track).as("the committed structured-output fixture must still be on disk").isPresent();

		ChatResponse response = new VcrTrackMapper().toChatResponse(track.get());

		VcrAssertions.assertThat(response)
			.hasJsonField("/carrier", "Turkish Airlines")
			.hasJsonField("/estimatedDays", 9)
			.hasJsonFieldOfType("/estimatedDays", JsonNodeType.NUMBER)
			.extractingText()
			.contains("Turkish Airlines");
	}

}
