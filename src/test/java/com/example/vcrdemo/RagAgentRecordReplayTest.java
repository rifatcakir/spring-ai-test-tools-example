package com.example.vcrdemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How you test a RAG agent's <em>generation</em> step deterministically: retrieval and
 * generation are two separate concerns, and this library caches the second one, not the
 * first. A real retrieval step (a vector store lookup) is not part of what gets recorded
 * here -- what matters for this test is that whatever documents retrieval hands back get
 * injected into the prompt, and the model's answer is grounded in them. That is exactly
 * the shape a {@code QuestionAnswerAdvisor}-style RAG pipeline produces by the time a
 * request reaches this library's own advisor: a system message carrying retrieved
 * context, followed by the user's question.
 *
 * <p><strong>Why the retrieved facts are invented, not real ones.</strong> If the answer
 * to the question were something the model could already know from training data (say,
 * "what is the capital of France"), a correct-looking answer would prove nothing --
 * the model could have guessed it without ever reading the injected context. The two
 * "documents" below describe a fictional company's fictional return policy. There is no
 * way {@code llama3.2:1b} has this memorized; the only way the recorded answer can be
 * right is if it actually used the retrieved text. One of the two documents is
 * irrelevant to the question on purpose too, the same way a real retriever's top-k
 * results are rarely all on-topic -- proving the model picked the right one out of more
 * than one candidate, not just parroted the only document it was given.
 */
@SpringBootTest(properties = { "spring.ai.test.vcr.mode=REPLAY_ONLY",
		"spring.ai.test.vcr.cache-directory=src/test/resources/llm-cache/rag-agent" })
class RagAgentRecordReplayTest {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	private static final String RELEVANT_DOCUMENT = "Document: Nefeli Robotics Return Policy. Nefeli Robotics "
			+ "offers a 45-day return window on all drone products, but only if the original packaging is kept. "
			+ "Refunds are issued to the original payment method within 5 business days of the returned item "
			+ "being received.";

	private static final String IRRELEVANT_DOCUMENT = "Document: Nefeli Robotics Office Locations. Nefeli "
			+ "Robotics has offices in Lisbon and Nairobi, and a warehouse in Rotterdam.";

	@Test
	void answerIsGroundedInTheRetrievedDocumentNotTheIrrelevantOne() throws IOException {
		ChatClient chatClient = this.chatClientBuilder.build();
		Prompt prompt = new Prompt(List.of(
				new SystemMessage("Answer the user's question using only the information in the documents below. "
						+ "If a document is not relevant to the question, ignore it.\n\n" + RELEVANT_DOCUMENT + "\n\n"
						+ IRRELEVANT_DOCUMENT),
				new UserMessage("What is Nefeli Robotics' return policy?")));

		String first = chatClient.prompt(prompt).call().content();
		String second = chatClient.prompt(prompt).call().content();

		// The committed fixture's exact recorded answer -- not just "mentions returns",
		// but the specific fictional numbers that only exist in RELEVANT_DOCUMENT, proving
		// this is grounded generation, not a guess.
		assertThat(first).as("the committed fixture's exact recorded answer")
			.contains("45")
			.contains("packaging");
		assertThat(first).as("the irrelevant document's content must not leak into an answer about the return policy")
			.doesNotContain("Lisbon")
			.doesNotContain("Nairobi")
			.doesNotContain("Rotterdam");
		assertThat(second).as("a replay must return exactly what was recorded").isEqualTo(first);

		Path cacheDirectory = Path.of("src/test/resources/llm-cache/rag-agent");
		try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
			assertThat(fixtures).as("exactly one fixture for this one distinct grounded prompt").hasSize(1);
		}
	}

}
