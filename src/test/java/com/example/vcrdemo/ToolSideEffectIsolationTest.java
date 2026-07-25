package com.example.vcrdemo;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.rifatcakir.springai.testtools.recorder.junit.VcrTool;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolMode;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scenario tool isolation (docs/TOOL-ISOLATION-PRD.md, the library's main repo)
 * exists for: a {@code @Tool} method with a genuine, observable side effect alongside
 * its return value — here, incrementing a counter standing in for "wrote an audit-log
 * entry recording that this order was checked" — rather than a pure lookup like {@link
 * ToolCallingRecordReplayTest}'s {@code getOrderStatus}, which has nothing to isolate.
 *
 * <p>Deliberately reuses that same tool's proven prompt/description shape (a direct
 * status question, "Use the tool ... do not guess") rather than an imperative
 * ("send this notification") phrasing: against the small {@code llama3.2:1b} model this
 * project runs against, an imperative-only prompt was empirically observed to sometimes
 * produce a hallucinated, malformed pseudo-tool-call as plain text instead of a real
 * tool invocation — confirmed by actually recording it and inspecting the fixture, not
 * assumed. The question-shaped prompt reliably triggers a genuine tool call.
 *
 * <p>Same {@code @SpringBootTest} config for both test methods (default {@code
 * VcrToolMode.REPLAY_FROM_CASSETTE}, {@code INSIDE_TOOL_LOOP}, {@code REPLAY_ONLY} chat
 * mode against committed fixtures) — the only thing that differs between them is {@link
 * VcrTool @VcrTool}'s per-test override, the same escape-hatch shape {@link
 * VcrAnnotationEscapeHatchTest} already demonstrates for {@code VcrMode}.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY", "spring.ai.test.vcr.scope=INSIDE_TOOL_LOOP",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/tool-side-effect-isolation",
		"spring.ai.test.vcr.tool.cache-directory=src/test/resources/llm-cache-tool/side-effect-demo" })
class ToolSideEffectIsolationTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	private static final String PROMPT = "What is the status of order ORD-4471? Use the tool to find out, do not guess.";

	/**
	 * A real {@code @Tool} method, not a mock. Its side effect (the counter) stands in
	 * for something a real order-status lookup might genuinely do alongside answering —
	 * write an audit-log entry, increment a metrics counter, ping a monitoring webhook —
	 * work with a real-world consequence that a test should not want to repeat on every
	 * replay.
	 */
	static class AuditedOrderStatusTool {

		final AtomicInteger auditLogEntriesWritten = new AtomicInteger();

		@Tool(description = "Get the current shipping status of a customer order by its order id")
		String getOrderStatus(String orderId) {
			this.auditLogEntriesWritten.incrementAndGet();
			return "shipped, arriving in 3 days";
		}

	}

	@Test
	void defaultReplayFromCassetteNeverWritesTheAuditLogEntryTwice() {
		AuditedOrderStatusTool tool = new AuditedOrderStatusTool();
		ChatClient chatClient = this.chatClientBuilder.build();

		chatClient.prompt().user(PROMPT).tools(tool).call().content();
		chatClient.prompt().user(PROMPT).tools(tool).call().content();

		assertThat(tool.auditLogEntriesWritten)
			.as("VcrToolMode.REPLAY_FROM_CASSETTE (the default): with the tool-execution fixture "
					+ "already committed, the real @Tool method never runs at all -- the audit-log side "
					+ "effect never fires, no matter how many times this test re-runs")
			.hasValue(0);
	}

	@Test
	@VcrTool(mode = VcrToolMode.EXECUTE_REAL)
	void executeRealWritesTheAuditLogEntryOnEveryCall() {
		AuditedOrderStatusTool tool = new AuditedOrderStatusTool();
		ChatClient chatClient = this.chatClientBuilder.build();

		chatClient.prompt().user(PROMPT).tools(tool).call().content();
		chatClient.prompt().user(PROMPT).tools(tool).call().content();

		assertThat(tool.auditLogEntriesWritten)
			.as("@VcrTool(mode = EXECUTE_REAL): the explicit opt-in for asserting the real tool actually "
					+ "ran -- every call reaches the real @Tool method, side effect included, exactly "
					+ "like this library's pre-isolation default used to")
			.hasValue(2);
	}

}
