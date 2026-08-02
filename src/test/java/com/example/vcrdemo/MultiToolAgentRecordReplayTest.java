package com.example.vcrdemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolCallingRecordReplayTest} shows one {@code @Tool} method. What that test
 * cannot show: an agent with a genuine <em>choice</em> to make. This one registers two
 * tools with the model -- {@link #getOrderStatus} (the same tool and prompt
 * {@link ToolCallingRecordReplayTest} already proves this model calls reliably) plus
 * {@link #getWeatherForCity}, a decoy from a completely different domain -- so the
 * recorded fixture proves the model picked the right tool out of more than one
 * candidate, not just called the only tool it had. Deliberately kept to a single string
 * argument per tool, the same shape as {@link ToolCallingRecordReplayTest}'s own proven
 * tool: {@code llama3.2:1b} is small enough that a multi-argument tool call is a real,
 * separate reliability risk this scenario does not need to take on to make its point.
 *
 * <p>Same tool-call isolation story as {@link ToolCallingRecordReplayTest}: with both
 * fixtures (the two model turns, and the tool-execution fixture) already committed,
 * neither real {@code @Tool} method runs at all on replay -- {@code
 * VcrToolMode.REPLAY_FROM_CASSETTE}, the default.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY", "spring.ai.test.vcr.scope=INSIDE_TOOL_LOOP",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/multi-tool-agent" })
class MultiToolAgentRecordReplayTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	/**
	 * Two real {@code @Tool} methods on one object, not mocks -- the point is that the
	 * unused one's counter stays at zero too, proving the model didn't call it "just in
	 * case," and the used one's counter stays at zero on replay, proving isolation.
	 */
	static class OrderAndWeatherTools {

		final AtomicInteger orderStatusInvocations = new AtomicInteger();

		final AtomicInteger weatherInvocations = new AtomicInteger();

		@Tool(description = "Get the current shipping status of a customer order by its order id")
		String getOrderStatus(String orderId) {
			this.orderStatusInvocations.incrementAndGet();
			return "shipped, arriving in 2 days";
		}

		@Tool(description = "Get the current weather for a city")
		String getWeatherForCity(String city) {
			this.weatherInvocations.incrementAndGet();
			return "sunny, 21C";
		}

	}

	@Test
	void modelPicksTheOrderStatusToolNotTheWeatherToolAndReplaysWithBothIsolated() throws IOException {
		OrderAndWeatherTools tools = new OrderAndWeatherTools();
		ChatClient chatClient = this.chatClientBuilder.build();
		String prompt = "What is the status of order ORD-4471? Use the tool to find out, do not guess.";

		String firstResponse = chatClient.prompt().user(prompt).tools(tools).call().content();
		String secondResponse = chatClient.prompt().user(prompt).tools(tools).call().content();

		// REPLAY_ONLY against committed fixtures: a known, fixed final answer.
		assertThat(firstResponse).as("the committed second-turn fixture's exact recorded final answer")
			.isEqualTo("The status of order ORD-4471 is \"shipped\" with an estimated delivery time of \"in 2 days\".");
		assertThat(secondResponse).as("a replay must return exactly what was recorded").isEqualTo(firstResponse);
		assertThat(tools.weatherInvocations)
			.as("the model never even attempted the wrong tool -- and REPLAY_FROM_CASSETTE means it wouldn't "
					+ "matter if it had, but a real agent choosing correctly is the point of this scenario")
			.hasValue(0);
		assertThat(tools.orderStatusInvocations)
			.as("VcrToolMode.REPLAY_FROM_CASSETTE (the default): the real @Tool method never runs at all, "
					+ "not even once, for either call -- see ToolSideEffectIsolationTest for the EXECUTE_REAL opt-in")
			.hasValue(0);

		Path cacheDirectory = Path.of("src/test/resources/llm-cache/multi-tool-agent");
		VcrTrackStore store = new VcrTrackStore(cacheDirectory);
		List<Path> written;
		try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
			written = fixtures.toList();
		}
		assertThat(written).as("one fixture per model turn -- two turns for one tool call").hasSize(2);

		// Read every actual tool CALL recorded across both fixtures -- deliberately not a
		// raw text search: both tools' names and schemas legitimately appear in every
		// fixture's own "tools" listing (what was *offered* to the model), so a substring
		// check can't tell "offered" apart from "actually called". Reading the structured
		// VcrTrack and walking only toolCalls entries can.
		List<String> calledToolNames = written.stream()
			.map(path -> store.read(path.getFileName().toString().replace(".json", "")).orElseThrow())
			.flatMap(track -> Stream.concat(
					track.request().messages().stream().flatMap(m -> m.toolCalls().stream()),
					track.response().generations().stream().flatMap(g -> g.toolCalls().stream())))
			.map(VcrTrack.ToolCallSnapshot::name)
			.toList();

		// Appears twice by design, not a bug in this assertion: the first turn's response
		// records the pending call, and the second turn's request replays it again as
		// conversation history -- both must show only getOrderStatus, never the decoy.
		assertThat(calledToolNames).as("the recorded call must be getOrderStatus, never getWeatherForCity -- the "
				+ "model chose correctly between the two tools it was offered")
			.isNotEmpty()
			.containsOnly("getOrderStatus");
	}

}
