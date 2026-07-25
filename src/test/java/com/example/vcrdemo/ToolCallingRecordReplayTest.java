package com.example.vcrdemo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
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
 * <p>What matters here that {@link RecordReplayBasicsTest} doesn't need to show: this is
 * a <em>two-turn</em> conversation, so recording it writes two model-turn fixtures, not
 * one. <strong>The real {@code @Tool} method itself (see {@link OrderStatusTool} below)
 * never runs at all in this committed, steady-state test</strong> -- its own
 * tool-execution fixture is committed too, so both calls replay both the model turns and
 * the tool invocation from disk -- because tool-call isolation
 * (docs/TOOL-ISOLATION-PRD.md in the main library repo) defaults to full isolation now.
 * See {@link ToolSideEffectIsolationTest} for a side-by-side demo of this default versus
 * the {@code @VcrTool(mode = VcrToolMode.EXECUTE_REAL)} opt-in that recovers this
 * library's older behaviour (the real tool re-running on every replay).
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
	void toolCallingConversationRecordsTwoTurnsAndReplaysBothWhileTheRealToolStaysIsolated() throws IOException {
		OrderStatusTool orderStatusTool = new OrderStatusTool();
		ChatClient chatClient = this.chatClientBuilder.build();
		String prompt = "What is the status of order ORD-4471? Use the tool to find out, do not guess.";

		String firstResponse = chatClient.prompt().user(prompt).tools(orderStatusTool).call().content();
		String secondResponse = chatClient.prompt().user(prompt).tools(orderStatusTool).call().content();

		// REPLAY_ONLY against committed fixtures: this is a known, fixed final answer,
		// not "whatever the model happens to say" -- asserting the exact text proves a
		// real replay happened, not just that some non-empty string came back.
		assertThat(firstResponse).as("the committed second-turn fixture's exact recorded final answer")
			.isEqualTo("The status of order ORD-4471 is \"shipped\" and it will arrive in 2 days.");
		assertThat(secondResponse).as("a replay must return exactly what was recorded").isEqualTo(firstResponse);
		assertThat(orderStatusTool.invocations)
			.as("VcrToolMode.REPLAY_FROM_CASSETTE (the default): with the tool-execution fixture "
					+ "already committed, the real @Tool method never runs at all -- not even once -- "
					+ "for either call. Full isolation, not just replayed model turns. See "
					+ "ToolSideEffectIsolationTest for the EXECUTE_REAL opt-in that recovers the old "
					+ "always-re-runs behaviour.")
			.hasValue(0);

		Path cacheDirectory = Path.of("src/test/resources/llm-cache/tool-calling");
		String combinedFixtureContent;
		try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
			List<Path> written = fixtures.toList();
			assertThat(written).as("one fixture per model turn -- two turns for one tool call").hasSize(2);
			combinedFixtureContent = written.stream().map(ToolCallingRecordReplayTest::readQuietly)
				.collect(Collectors.joining("\n"));
		}
		// Not just "a tool call happened somewhere" -- the exact function name and
		// argument the model committed to calling, across the two fixture files.
		assertThat(combinedFixtureContent)
			.as("the recorded tool call must be getOrderStatus with the order id from the prompt, not just any call")
			.contains("\"name\" : \"getOrderStatus\"")
			.contains("orderId")
			.contains("ORD-4471");
	}

	private static String readQuietly(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
