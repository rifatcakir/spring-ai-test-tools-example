package com.example.vcrdemo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import io.github.rifatcakir.springai.testtools.recorder.remote.RemoteChatFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.track.ChatFixtureStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Server and client: the same application, with its fixtures on a shared
 * <a href="https://github.com/rifatcakir/spring-ai-test-tools-server">Cassette Server</a>
 * instead of {@code src/test/resources}. This is how a deployed test environment, or several
 * services, share one set of recordings.
 *
 * <p>The only change on the application side is configuration: {@code store: remote}, the
 * server's URL and a namespace (below). No code changes; the {@code ChatClient} call is the same
 * one {@link ReplayOnlySealedCiTest} makes.
 *
 * <p>The server here is the real published image, started by Testcontainers. Its namespace is
 * seeded with the very fixture file that {@link ReplayOnlySealedCiTest} replays from disk,
 * copied in unchanged: the server stores fixtures in exactly the library's file layout, so a
 * fixture directory and a server namespace are interchangeable.
 *
 * <p>Note what this test does <em>not</em> set: {@code spring.ai.test.vcr.mode}. With no mode
 * of its own, the application follows the server's namespace default, which is started as
 * {@code REPLAY_ONLY}. An admin can flip it at runtime ({@code PUT /__admin/namespaces/{ns}/mode})
 * without touching the application; a mode set in the application always wins.
 *
 * <p>Needs Docker (skipped without it), but no model: like the other replay tests, nothing
 * here can reach Ollama.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = { "spring.ai.test.vcr.store=remote", "spring.ai.test.vcr.remote.namespace=example" })
class CassetteServerRemoteStoreTest {

	private static final String ADMIN_TOKEN = "example-admin-token";

	/**
	 * Keep the server on the same version as the library (0.2.0 here); the server refuses
	 * fixture formats newer than it knows.
	 */
	@Container
	static final GenericContainer<?> CASSETTE_SERVER = new GenericContainer<>(
			"ghcr.io/rifatcakir/spring-ai-test-tools-server:0.2.0")
		.withExposedPorts(9090)
		.withEnv("CASSETTE_ADMIN_TOKEN", ADMIN_TOKEN)
		// The namespace default for clients that set no mode.
		.withEnv("CASSETTE_DEFAULT_MODE", "REPLAY_ONLY")
		// Seed namespace "example" with the committed fixture ReplayOnlySealedCiTest uses.
		.withCopyFileToContainer(MountableFile.forHostPath(Path.of("src/test/resources/llm-cache/replay-only"), 0755),
				"/data/cassettes/example/llm-cache")
		.waitingFor(Wait.forHttp("/actuator/health").forPort(9090).forStatusCode(200));

	@DynamicPropertySource
	static void cassetteServer(DynamicPropertyRegistry registry) {
		registry.add("spring.ai.test.vcr.remote.url", CassetteServerRemoteStoreTest::serverUrl);
	}

	private static String serverUrl() {
		return "http://" + CASSETTE_SERVER.getHost() + ":" + CASSETTE_SERVER.getMappedPort(9090);
	}

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Autowired
	private ChatFixtureStore fixtureStore;

	@Test
	void replaysAFixtureStoredOnTheServer() {
		assertThat(this.fixtureStore).as("store: remote really is in effect").isInstanceOf(RemoteChatFixtureStore.class);

		String response = this.chatClientBuilder.build()
			.prompt()
			.user("Reply with exactly one word: sealed")
			.call()
			.content();

		// The same recorded answer ReplayOnlySealedCiTest gets from the file on disk, this time
		// served by the Cassette Server. No model is reachable, so it cannot have come from one.
		assertThat(response).isEqualTo("No.");
	}

	@Test
	void anUnrecordedPromptFailsLoudlyAndTheServerRecordsTheMiss() throws Exception {
		String prompt = "This exact sentence has deliberately never been recorded on the Cassette Server.";

		VcrCacheMissException miss = catchThrowableOfType(VcrCacheMissException.class,
				() -> this.chatClientBuilder.build().prompt().user(prompt).call().content());

		// REPLAY_ONLY came from the server's namespace default; the application set no mode.
		assertThat(miss).as("REPLAY_ONLY refuses to call the model on a miss").isNotNull();
		assertThat(miss.getExpectedLocation()).as("the miss names the fixture's URL on the server")
			.isEqualTo(serverUrl() + "/api/v1/ns/example/chat/" + miss.getHash());
		// And the server keeps a log of what was looked up and not found, for whoever is
		// debugging a sealed environment without access to the application's logs.
		assertThat(admin("/__admin/namespaces/example/misses")).contains(miss.getHash());
	}

	@Test
	void theAdminApiShowsWhatIsStored() throws Exception {
		assertThat(admin("/__admin/namespaces/example/fixtures?kind=chat"))
			.contains("37a6ec93b7bb29a42fda37791dbd7d4030188766ab91b2cf87ff00a10203e8c6")
			.contains("Reply with exactly one word: sealed");
		assertThat(admin("/__admin/namespaces")).contains("\"mode\":\"REPLAY_ONLY\"");
	}

	@Test
	void theAdminApiNeedsTheToken() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(serverUrl() + "/__admin/namespaces")).build(),
					HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(401);
	}

	private static String admin(String path) throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(serverUrl() + path))
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(path).isEqualTo(200);
		return response.body();
	}

}
