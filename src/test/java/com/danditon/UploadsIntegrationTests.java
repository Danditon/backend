package com.danditon;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(UploadsIntegrationTests.ErrorDispatchObservation.class)
@DirtiesContext
class UploadsIntegrationTests {

	private static final Map<String, byte[]> IMAGES = Map.of(
		"png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3},
		"jpg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3},
		"webp", new byte[]{'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'});
	private static final String SENTINEL = "PRIVATE-UPLOAD-SENTINEL";
	private static final String UNAUTHORIZED = "{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요해요\"}";
	private static final String ALLOWED_ORIGIN = "https://marshrnello.cloud";
	private static final Map<String, String> ERROR_DISPATCHES = new ConcurrentHashMap<>();
	@TempDir static Path temporary;
	@LocalServerPort private int port;
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@DynamicPropertySource
	static void storageRoot(DynamicPropertyRegistry properties) {
		properties.add("danditon.storage.root", () -> temporary.resolve("uploads space").toString());
	}

	@BeforeAll
	static void createFixtures() throws Exception {
		Path root = temporary.resolve("uploads space");
		for (String category : new String[]{"quest", "submission"}) {
			Files.createDirectories(root.resolve(category));
			for (var image : IMAGES.entrySet()) {
				Files.write(root.resolve(category).resolve("image." + image.getKey()), image.getValue());
			}
			Files.writeString(root.resolve(category).resolve(category + "-only.png"), SENTINEL);
		}
		Files.createDirectories(root.resolve("quest/nested"));
		Files.createDirectories(root.resolve("unknown"));
		for (String path : new String[]{"root-secret.png", "unknown/unknown-secret.png", "quest/nested/listing-secret.png"}) {
			Files.writeString(root.resolve(path), SENTINEL);
		}
		Files.writeString(temporary.resolve("outside.png"), SENTINEL);
	}

	@ParameterizedTest
	@CsvSource({"quest,png,image/png", "submission,png,image/png", "quest,jpg,image/jpeg",
		"submission,jpg,image/jpeg", "quest,webp,image/webp", "submission,webp,image/webp"})
	void anonymousGetReturnsExactImage(String category, String extension, String contentType) throws Exception {
		var response = send(request("GET", "/uploads/" + category + "/image." + extension));
		assertEquals(200, response.statusCode());
		assertEquals(contentType, response.headers().firstValue("content-type").orElseThrow());
		assertArrayEquals(IMAGES.get(extension), response.body());
		assertEquals(sha256(IMAGES.get(extension)), sha256(response.body()));
	}

	@ParameterizedTest
	@CsvSource({"quest,png", "submission,png", "quest,jpg", "submission,jpg", "quest,webp", "submission,webp"})
	void anonymousHeadMatchesGetHeadersWithoutBody(String category, String extension) throws Exception {
		String path = "/uploads/" + category + "/image." + extension;
		var head = send(request("HEAD", path));
		assertEquals(200, head.statusCode());
		assertEquals(0, head.body().length);
		var get = send(request("GET", path));
		assertEquals(200, get.statusCode());
		for (String header : new String[]{"content-type", "content-length", "last-modified", "accept-ranges",
			"x-content-type-options", "x-frame-options", "cache-control"}) {
			assertEquals(get.headers().firstValue(header), head.headers().firstValue(header), header);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"/uploads/quest/submission-only.png", "/uploads/submission/quest-only.png",
		"/uploads/root-secret.png", "/uploads/unknown/unknown-secret.png", "/uploads/quest/missing.png",
		"/uploads/submission/missing.png", "/uploads/quest/", "/uploads/submission/", "/uploads/quest/nested/", "/uploads/"})
	void unavailablePathsArePublic404(String path) throws Exception {
		for (String method : new String[]{"GET", "HEAD"}) {
			var response = send(request(method, path));
			assertEquals(404, response.statusCode(), path);
			assertFalse(new String(response.body(), StandardCharsets.UTF_8).contains(SENTINEL));
			assertFalse(new String(response.body(), StandardCharsets.UTF_8).contains("listing-secret.png"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"/uploads/../outside.png", "/uploads/%2e%2e/outside.png",
		"/uploads/quest/../../outside.png", "/uploads/quest/%2e%2e/%2e%2e/outside.png",
		"/uploads/quest/../root-secret.png", "/uploads/quest/../submission/submission-only.png",
		"/uploads/submission/../quest/quest-only.png", "/uploads/quest/%2e%2e/submission/submission-only.png",
		"/uploads/quest/..%2f..%2foutside.png", "/uploads/quest/%252e%252e/%252e%252e/outside.png",
		"/uploads/quest/..%5c..%5coutside.png"})
	void traversalCannotDiscloseFiles(String path) throws Exception {
		var response = send(request("GET", path));
		assertTrue(Set.of(400, 401, 404).contains(response.statusCode()), path);
		if (response.statusCode() == 401) {
			String normalized = URI.create(URI.create(path).getPath()).normalize().getPath();
			if (normalized.startsWith("/uploads/")) {
				assertEquals("/error", ERROR_DISPATCHES.get(path), "401 must follow rejection outside uploads: " + path);
			}
			assertUnauthorized(response);
		}
		assertFalse(new String(response.body(), StandardCharsets.UTF_8).contains(SENTINEL));
	}

	@ParameterizedTest
	@ValueSource(strings = {"GET", "HEAD"})
	void publicReadsAndPreflightAllowConfiguredOrigin(String method) throws Exception {
		for (String category : new String[]{"quest", "submission"}) {
			String path = "/uploads/" + category + "/image.png";
			var response = send(request(method, path).header("Origin", ALLOWED_ORIGIN));
			assertEquals(200, response.statusCode());
			assertCors(response);
			var preflight = send(request("OPTIONS", path).header("Origin", ALLOWED_ORIGIN)
				.header("Access-Control-Request-Method", method)
				.header("Access-Control-Request-Headers", "Authorization,Content-Type"));
			assertEquals(200, preflight.statusCode());
			assertCors(preflight);
			assertEquals("GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS",
				preflight.headers().firstValue("access-control-allow-methods").orElseThrow());
			assertEquals("Authorization, Content-Type", preflight.headers().firstValue("access-control-allow-headers").orElseThrow());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"OPTIONS", "POST", "PUT", "PATCH", "DELETE"})
	void otherMethodsRemainProtected(String method) throws Exception {
		for (String category : new String[]{"quest", "submission"}) {
			assertUnauthorized(send(request(method, "/uploads/" + category + "/image.png")));
		}
	}

	@Test
	void malformedBearerAndUnrelatedPathRemainProtected() throws Exception {
		for (String category : new String[]{"quest", "submission"}) {
			assertUnauthorized(send(request("GET", "/uploads/" + category + "/image.png")
				.header("Authorization", "Bearer malformed")));
		}
		assertUnauthorized(send(request("GET", "/api/protected")));
	}

	private void assertUnauthorized(HttpResponse<byte[]> response) {
		assertEquals(401, response.statusCode());
		assertEquals(UNAUTHORIZED, new String(response.body(), StandardCharsets.UTF_8));
		assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/json"));
		assertTrue(response.headers().firstValue("set-cookie").isEmpty());
	}

	private void assertCors(HttpResponse<byte[]> response) {
		assertEquals(ALLOWED_ORIGIN, response.headers().firstValue("access-control-allow-origin").orElseThrow());
		assertTrue(response.headers().firstValue("access-control-allow-credentials").isEmpty());
	}

	private HttpRequest.Builder request(String method, String path) {
		return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody());
	}

	private HttpResponse<byte[]> send(HttpRequest.Builder builder) throws Exception {
		var request = builder.build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
		System.out.println("UPLOAD_HTTP " + request.method() + " " + request.uri().getRawPath()
			+ " status=" + response.statusCode() + " bytes=" + response.body().length + " sha256=" + sha256(response.body()));
		return response;
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ErrorDispatchObservation {
		@Bean
		FilterRegistrationBean<jakarta.servlet.Filter> observeRejectedRequests() {
			var registration = new FilterRegistrationBean<jakarta.servlet.Filter>((request, response, chain) -> {
				var http = (HttpServletRequest) request;
				String original = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
				ERROR_DISPATCHES.put(original, http.getRequestURI());
				System.out.println("UPLOAD_REJECTION original=" + original + " dispatcher=ERROR target=" + http.getRequestURI());
				chain.doFilter(request, response);
			});
			registration.setDispatcherTypes(DispatcherType.ERROR);
			registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
			return registration;
		}
	}

	@AfterAll
	static void stopServerAndProvePortClosed(@Autowired ServletWebServerApplicationContext context) throws Exception {
		int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
		context.getWebServer().stop();
		try (var socket = new Socket()) {
			assertThrows(java.net.ConnectException.class,
				() -> socket.connect(new InetSocketAddress("127.0.0.1", port), 1000));
		}
		System.out.println("UPLOAD_TEARDOWN port=" + port + " connection=refused fixtures=JUnit-TempDir-cleanup");
	}
}
