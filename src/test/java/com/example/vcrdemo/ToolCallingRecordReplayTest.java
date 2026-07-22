package com.example.vcrdemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How you test a Spring AI {@code @Tool} method deterministically: {@code
 * VcrScope.INSIDE_TOOL_LOOP} caches one fixture per model turn instead of one for the
 * whole round trip, so a tool-calling conversation -- the model asks to call the tool,
 * the real {@code @Tool} method runs, its result goes back, the model gives a final
 * answer -- replays as two fixtures instead of collapsing into one opaque interaction.
 *
 * <p>Two things matter here that {@link RecordReplayBasicsTest} doesn't need to show:
 * this is a <em>two-turn</em> conversation, so recording it writes two fixtures, not
 * one; and the real {@code @Tool} method (see {@link OrderStatusTool} below) keeps
 * running on every replay, not just on the first, live call -- that's the whole point
 * of {@code INSIDE_TOOL_LOOP} over the default {@code OUTSIDE_TOOL_LOOP}, where a
 * replay would skip the tool loop (and the tool) entirely.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY", "spring.ai.test.vcr.scope=INSIDE_TOOL_LOOP",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/tool-calling" })
class ToolCallingRecordReplayTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	/**
	 * A real {@code @Tool} method, not a mock -- the point of this test is that this
	 * keeps running on replay, so a mock would prove nothing.
	 */
	static class OrderStatusTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(description = "Get the current shipping status of a customer order by its order id")
		String getOrderStatus(String orderId) {
			this.invocations.incrementAndGet();
			return "shipped, arriving in 2 days";
		}

	}

	@Test
	void toolCallingConversationRecordsTwoTurnsAndReplaysBothWithTheRealToolStillRunning() throws IOException {
		OrderStatusTool orderStatusTool = new OrderStatusTool();
		ChatClient chatClient = this.chatClientBuilder.build();
		String prompt = "What is the status of order ORD-4471? Use the tool to find out, do not guess.";

		String firstResponse = chatClient.prompt().user(prompt).tools(orderStatusTool).call().content();
		String secondResponse = chatClient.prompt().user(prompt).tools(orderStatusTool).call().content();

		assertThat(firstResponse).isNotBlank();
		assertThat(secondResponse).as("a replay must return exactly what was recorded").isEqualTo(firstResponse);
		assertThat(orderStatusTool.invocations)
			.as("INSIDE_TOOL_LOOP's real point: the real @Tool method runs again on replay, "
					+ "even though neither model turn around it reaches the network the second time")
			.hasValue(2);

		Path cacheDirectory = Path.of("src/test/resources/llm-cache/tool-calling");
		try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
			assertThat(fixtures).as("one fixture per model turn -- two turns for one tool call").hasSize(2);
		}
	}

}
