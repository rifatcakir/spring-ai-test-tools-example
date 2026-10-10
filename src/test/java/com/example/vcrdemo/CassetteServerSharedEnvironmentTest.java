package com.example.vcrdemo;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import io.github.rifatcakir.springai.testtools.recorder.remote.CassetteServerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What a shared test environment looks like with a Cassette Server, scenario by scenario.
 *
 * <p>{@link CassetteServerRemoteStoreTest} shows the basics with one application. Here every
 * scenario starts <em>separate application instances</em> (separate Spring contexts, as separate
 * deployments or replicas would be) against one server, because that is what the server is for:
 * one set of recordings for everything in an environment.
 *
 * <p>The "model" is a small counting stand-in, so recording works in CI with no real model and
 * every scenario can assert how many times the model was actually called. Its answers are
 * numbered ("answer #1 ..."), so a replayed answer is recognisable by its number. Nothing about
 * the library or the server is faked: the real image runs in Docker, and each instance is the
 * real application with {@code store: remote}.
 *
 * <p>Each scenario uses its own namespace, so they stay independent. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class CassetteServerSharedEnvironmentTest {

	private static final String ADMIN_TOKEN = "example-admin-token";

	private static final String QUESTION = "What is the capital of France?";

	@Container
	static final GenericContainer<?> CASSETTE_SERVER = new GenericContainer<>(
			"ghcr.io/rifatcakir/spring-ai-test-tools-server:0.2.0")
		.withExposedPorts(9090)
		.withEnv("CASSETTE_ADMIN_TOKEN", ADMIN_TOKEN)
		.waitingFor(Wait.forHttp("/actuator/health").forPort(9090).forStatusCode(200));

	private final List<ConfigurableApplicationContext> instances = new ArrayList<>();

	@AfterEach
	void stopInstances() {
		this.instances.forEach(ConfigurableApplicationContext::close);
	}

	@Test
	@DisplayName("two instances share one recording: the second never reaches the model")
	void twoInstancesShareOneRecording() {
		CountingModel modelOfA = new CountingModel();
		CountingModel modelOfB = new CountingModel();

		String fromA = ask(instance("shared", modelOfA), QUESTION);
		String fromB = ask(instance("shared", modelOfB), QUESTION);

		assertThat(fromA).isEqualTo("answer #1 to: " + QUESTION);
		assertThat(fromB).as("instance B replays what instance A recorded").isEqualTo(fromA);
		assertThat(modelOfA.calls).hasValue(1);
		assertThat(modelOfB.calls).as("B's model was never called").hasValue(0);
	}

	@Test
	@DisplayName("an admin re-records an environment and seals it again, without redeploying anything")
	void anAdminReRecordsWithoutRedeploying() throws Exception {
		CountingModel model = new CountingModel();
		// One instance for the whole scenario: it sets no mode, so it follows the namespace.
		ConfigurableApplicationContext app = instance("rerecord", model);
		assertThat(ask(app, QUESTION)).isEqualTo("answer #1 to: " + QUESTION);

		admin("PUT", "/__admin/namespaces/rerecord/mode", "{\"mode\":\"RECORD_ALWAYS\"}");
		assertThat(ask(app, QUESTION)).as("RECORD_ALWAYS goes to the model and replaces the recording")
			.isEqualTo("answer #2 to: " + QUESTION);

		admin("PUT", "/__admin/namespaces/rerecord/mode", "{\"mode\":\"REPLAY_ONLY\"}");
		assertThat(ask(app, QUESTION)).as("sealed again: the new recording replays").isEqualTo("answer #2 to: " + QUESTION);
		assertThatExceptionOfType(VcrCacheMissException.class)
			.as("and nothing unrecorded gets through to the model")
			.isThrownBy(() -> ask(app, "A question nobody recorded"));

		assertThat(model.calls).hasValue(2);
	}

	@Test
	@DisplayName("deleting a fixture on the server makes the next call record it again")
	void deletingAFixtureForcesAReRecord() throws Exception {
		CountingModel model = new CountingModel();
		ConfigurableApplicationContext app = instance("delete", model);
		ask(app, QUESTION);
		String hash = onlyChatFixtureHash("delete");

		admin("DELETE", "/__admin/namespaces/delete/fixtures/chat/" + hash, null);

		assertThat(ask(app, QUESTION)).isEqualTo("answer #2 to: " + QUESTION);
		assertThat(model.calls).hasValue(2);
	}

	@Test
	@DisplayName("recordings from the environment go back into the project: export, unzip, replay from files")
	void exportedRecordingsReplayFromFiles(@TempDir Path project) throws Exception {
		CountingModel recorder = new CountingModel();
		ask(instance("export", recorder), QUESTION);

		unzip(adminBytes("/__admin/namespaces/export/export"), project.resolve("src/test/resources"));

		// The zip is in the library's own layout, so it drops straight into src/test/resources.
		CountingModel noModel = new CountingModel();
		ConfigurableApplicationContext fromFiles = start(noModel, "spring.ai.test.vcr.store=file",
				"spring.ai.test.vcr.mode=REPLAY_ONLY",
				"spring.ai.test.vcr.cache-directory=" + project.resolve("src/test/resources/llm-cache"));
		assertThat(ask(fromFiles, QUESTION)).isEqualTo("answer #1 to: " + QUESTION);
		assertThat(noModel.calls).hasValue(0);
	}

	@Test
	@DisplayName("an unreachable server fails the call loudly: no silent model call, no fallback to files")
	void anUnreachableServerFailsLoudly() {
		CountingModel model = new CountingModel();
		ConfigurableApplicationContext app = start(model, "spring.ai.test.vcr.store=remote",
				"spring.ai.test.vcr.remote.url=http://localhost:1", "spring.ai.test.vcr.remote.namespace=down",
				"spring.ai.test.vcr.remote.connect-timeout=1s");

		assertThatExceptionOfType(CassetteServerException.class).isThrownBy(() -> ask(app, QUESTION))
			.withMessageContaining("http://localhost:1")
			.withMessageContaining("Not calling the real model");
		assertThat(model.calls).hasValue(0);
	}

	// ---------------------------------------------------------------- helpers

	/** An application instance using the Cassette Server, in {@code namespace}, with no mode of its own. */
	private ConfigurableApplicationContext instance(String namespace, CountingModel model) {
		return start(model, "spring.ai.test.vcr.store=remote", "spring.ai.test.vcr.remote.url=" + serverUrl(),
				"spring.ai.test.vcr.remote.namespace=" + namespace);
	}

	/**
	 * The real application ({@link VcrDemoApplication} with its auto-configuration), with the
	 * counting stand-in as its only chat model.
	 */
	private ConfigurableApplicationContext start(CountingModel model, String... properties) {
		List<String> all = new ArrayList<>(List.of(properties));
		all.add("spring.ai.model.chat=none");
		ConfigurableApplicationContext context = new SpringApplicationBuilder(VcrDemoApplication.class)
			.web(WebApplicationType.NONE)
			.properties(all.toArray(String[]::new))
			.initializers(ctx -> ctx.getBeanFactory().registerSingleton("countingChatModel", model))
			.run();
		this.instances.add(context);
		return context;
	}

	private static String ask(ConfigurableApplicationContext app, String question) {
		return app.getBean(ChatClient.Builder.class).build().prompt().user(question).call().content();
	}

	private static String serverUrl() {
		return "http://" + CASSETTE_SERVER.getHost() + ":" + CASSETTE_SERVER.getMappedPort(9090);
	}

	private static String onlyChatFixtureHash(String namespace) throws Exception {
		String fixtures = admin("GET", "/__admin/namespaces/" + namespace + "/fixtures?kind=chat", null);
		return fixtures.replaceAll("(?s).*\"hash\":\"([0-9a-f]{64})\".*", "$1");
	}

	private static String admin(String method, String path, String json) throws Exception {
		HttpRequest.BodyPublisher body = (json != null) ? HttpRequest.BodyPublishers.ofString(json)
				: HttpRequest.BodyPublishers.noBody();
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(serverUrl() + path))
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Content-Type", "application/json")
				.method(method, body)
				.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(method + " " + path).isBetween(200, 299);
		return response.body();
	}

	private static byte[] adminBytes(String path) throws Exception {
		HttpResponse<byte[]> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(serverUrl() + path))
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.build(), HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).isEqualTo(200);
		return response.body();
	}

	private static void unzip(byte[] zip, Path target) throws Exception {
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				Path file = target.resolve(entry.getName()).normalize();
				assertThat(file.startsWith(target)).isTrue();
				Files.createDirectories(file.getParent());
				Files.write(file, in.readAllBytes());
			}
		}
	}

	/** A stand-in model that numbers its answers and counts its calls. */
	static final class CountingModel implements ChatModel {

		final AtomicInteger calls = new AtomicInteger();

		@Override
		public ChatResponse call(Prompt prompt) {
			int n = this.calls.incrementAndGet();
			String text = "answer #" + n + " to: " + prompt.getUserMessage().getText();
			return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(text)))).build();
		}

	}

}
