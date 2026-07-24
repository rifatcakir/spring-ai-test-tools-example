package com.example.vcrdemo;

import org.junit.jupiter.api.Test;

import io.github.rifatcakir.springai.testtools.assertions.VcrAssertions;
import io.github.rifatcakir.springai.testtools.stub.VcrStubs;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Showcases spring-ai-test-tools's Stub layer -- {@code
 * io.github.rifatcakir.springai.testtools.stub.VcrStubs} -- for exactly what every other
 * test in this project cannot demonstrate: a scenario no real model will reliably
 * reproduce on demand. Every other showcase here needs a real {@code llama3.2:1b} call at
 * least once, to record the fixture being replayed; these tests never talk to a model at
 * all, not even at recording time -- there is no fixture, no cache directory, and (unlike
 * every {@code @SpringBootTest} elsewhere in this project) no Spring context either. That
 * absence is the point: {@code VcrStubs} is a plain Java utility, usable from the
 * plainest possible JUnit test.
 *
 * <p>Record/replay stays this project's headline story (see every other test here); this
 * file exists to show the two things it structurally cannot give you instead of pretending
 * a fixture could.
 */
class StubbingErrorScenariosTest {

	@Test
	void aFailingStubLetsYouTestErrorHandlingWithoutWaitingForARealTimeout() {
		// No real Ollama instance will reliably time out on command, so record/replay has
		// nothing to capture here -- this is exactly the gap VcrStubs.failingWith(...) fills.
		ChatModel model = VcrStubs.chatModel().failingWith(new RuntimeException("Ollama request timed out")).build();
		ChatClient chatClient = ChatClient.builder(model).build();

		assertThatThrownBy(() -> chatClient.prompt().user("what's the weather in Ankara?").call().content())
			.isInstanceOf(RuntimeException.class)
			.hasMessage("Ollama request timed out");
	}

	@Test
	void aLengthFinishReasonStubLetsYouAssertTruncationHandlingDeterministically() {
		// A real model occasionally gets cut off mid-answer once it hits a token limit,
		// but you cannot reliably provoke that on demand to record it -- withFinishReason
		// makes the scenario itself deterministic instead of hoping a live call truncates.
		ChatModel model = VcrStubs.chatModel()
			.respondingWith("The quick brown fox jumps over the la")
			.withFinishReason("length")
			.build();
		ChatClient chatClient = ChatClient.builder(model).build();

		ChatResponse response = chatClient.prompt().user("tell me a short story").call().chatResponse();

		VcrAssertions.assertThat(response).hasFinishReason("length").extractingText().endsWith("the la");
	}

	@Test
	void anUnconfiguredStubIsStillAValidResponseNeverNull() {
		// Partial definition + sensible defaults, the design center of this layer: a test
		// only states the one field it cares about, everything else gets a valid default.
		ChatModel model = VcrStubs.chatModel().build();
		ChatClient chatClient = ChatClient.builder(model).build();

		String response = chatClient.prompt().user("anything").call().content();

		assertThat(response).isEmpty();
	}

}
