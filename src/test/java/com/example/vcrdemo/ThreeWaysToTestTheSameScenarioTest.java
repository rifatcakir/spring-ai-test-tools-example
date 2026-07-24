package com.example.vcrdemo;

import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.junit.Vcr;
import io.github.rifatcakir.springai.testtools.stub.VcrStubs;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same scenario -- ask the assistant to acknowledge a request -- tested three
 * different ways, side by side, so the choice this library gives you per test is
 * concrete rather than abstract. All three hand back a {@code ChatModel}, so
 * {@code ChatClient.builder(model)...} is identical in every one of them; only which
 * {@code ChatModel} gets constructed differs.
 *
 * <p><strong>This class is {@code @SpringBootTest}-annotated only because one of the
 * three methods needs it</strong> (the real-model one, to get an autowired,
 * autoconfigured {@code ChatClient.Builder} pointed at a real Ollama). The two stub
 * methods below do not use that autowired builder at all -- they build their own
 * {@code ChatClient} from a plain {@code VcrStubs}-built {@code ChatModel} and would
 * work exactly the same way in an ordinary, non-Spring JUnit test. They live in this
 * annotated class purely so all three variants can be read and compared together in one
 * file; the Spring context they happen to run inside is incidental to them, not required
 * by them.
 */
@SpringBootTest(properties = "spring.ai.test.vcr.mode=REPLAY_ONLY")
class ThreeWaysToTestTheSameScenarioTest {

	private static final String PROMPT = "Reply with exactly one word: acknowledged";

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Test
	@Tag("integration")
	@Vcr(mode = VcrMode.BYPASS)
	void answersWithARealModel() {
		// Tagged integration and excluded from the default `mvn test` run (see
		// excludedGroups in pom.xml) -- this is the only one of the three that needs a
		// real, reachable Ollama every time. Run explicitly with `mvn test -Pintegration`.
		// @Vcr(mode = BYPASS) reaches the real model despite this class's own
		// REPLAY_ONLY setting, the same escape hatch VcrAnnotationEscapeHatchTest uses --
		// this.chatClientBuilder is the autoconfigured, VCR-customized builder, so this
		// is the real model reached through the same wiring the rest of this project
		// already uses, not a separately constructed one.
		ChatClient chatClient = this.chatClientBuilder.build();

		String answer = chatClient.prompt().user(PROMPT).call().content();

		// A real model's exact wording can't be pinned down without recording it --
		// unlike the two stub variants below, which answer exactly what they were told.
		assertThat(answer).isNotBlank();
	}

	@Test
	void answersWithAnInlineStub() {
		// No Spring, no Docker, no fixture -- the response is stated right here, in the
		// test itself. Reach for this when the exact answer matters to the assertion.
		ChatModel model = VcrStubs.chatModel().respondingWith("acknowledged").build();
		ChatClient chatClient = ChatClient.builder(model).build();

		String answer = chatClient.prompt().user(PROMPT).call().content();

		assertThat(answer).isEqualTo("acknowledged");
	}

	@Test
	void answersWithAFileSourcedStub() {
		// Same stub, same exact-match guarantee -- the response text just lives in
		// src/test/resources/responses/acknowledged.txt instead of a Java string
		// literal. Useful once a canned response gets long enough that a literal starts
		// hurting readability.
		ChatModel model = VcrStubs.chatModel().respondingWithContentOf("responses/acknowledged.txt").build();
		ChatClient chatClient = ChatClient.builder(model).build();

		String answer = chatClient.prompt().user(PROMPT).call().content();

		assertThat(answer).isEqualTo("acknowledged");
	}

}
