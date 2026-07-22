package com.example.vcrdemo;

import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.junit.Vcr;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application is configured as if this were a sealed CI run
 * ({@code REPLAY_ONLY}) -- and then this one test opts out of that seal for itself,
 * without weakening it for anything else. {@code @Vcr(mode = VcrMode.BYPASS)} reaches
 * the real model every time this runs, exactly the escape hatch a smoke test against a
 * real provider needs.
 *
 * <p>Tagged {@code integration} and excluded from the default {@code mvn test} run
 * (see {@code excludedGroups} in pom.xml) for exactly that reason: this test needs a
 * real, reachable Ollama every single time, unlike every other test in this project.
 * Run it explicitly with {@code mvn test -Pintegration}.
 */
@Tag("integration")
@SpringBootTest(properties = "spring.ai.test.vcr.mode=REPLAY_ONLY")
class VcrAnnotationEscapeHatchTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Test
	@Vcr(mode = VcrMode.BYPASS)
	void reachesTheRealModelDespiteTheSealedContext() {
		ChatClient chatClient = this.chatClientBuilder.build();

		String response = chatClient.prompt()
			.user("This prompt has never been recorded and never will be -- reply with exactly one word: live")
			.call()
			.content();

		assertThat(response).isNotBlank();
	}

}
