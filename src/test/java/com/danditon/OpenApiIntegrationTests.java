package com.danditon;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiIntegrationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Path EVIDENCE = Path.of(System.getenv().getOrDefault("AUTH_OPENAPI_EVIDENCE_DIR",
		".omo/evidence/auth-signup-login/task-8"));
	private static final Map<String, String> ERRORS = Map.of(
		"400", "{\"code\":\"INVALID_INPUT\",\"message\":\"입력값이 올바르지 않아요\"}",
		"401", "{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요해요\"}",
		"409", "{\"code\":\"DUPLICATE_EMAIL\",\"message\":\"이미 가입된 이메일이에요\"}",
		"415", "{\"code\":\"INVALID_INPUT\",\"message\":\"입력값이 올바르지 않아요\"}",
		"500", "{\"code\":\"INTERNAL_ERROR\",\"message\":\"서버 오류가 발생했어요\"}");
	@LocalServerPort private int port;
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private JsonNode spec;

	@BeforeAll
	void fetchCurrentGeneratedDocument() throws Exception {
		Files.createDirectories(EVIDENCE);
		Files.writeString(EVIDENCE.resolve("http-matrix.tsv"), "method\tpath\tstatus\tcontentType\tbytes\n");
		var response = send(HttpRequest.newBuilder(uri("/v3/api-docs")).GET());
		assertEquals(200, response.statusCode());
		spec = JSON.readTree(response.body());
		Files.writeString(EVIDENCE.resolve("generated-openapi.json"), response.body());
	}

	@ParameterizedTest
	@ValueSource(strings = {"signup", "login"})
	void publicPostOperationsHaveExactRequestAndResponseContracts(String action) {
		var path = spec.path("paths").path("/api/auth/" + action);
		assertEquals(Set.of("post"), path.propertyNames());
		var operation = path.path("post");
		assertEquals(action, operation.path("operationId").asText());
		assertEquals(JSON.readTree("[]"), operation.get("security"));
		assertEquals(JSON.readTree("[\"인증\"]"), operation.path("tags"));
		assertFalse(operation.path("summary").asText().isBlank());
		assertFalse(operation.path("description").asText().isBlank());
		assertTrue(operation.at("/requestBody/required").asBoolean());
		assertEquals(Set.of("application/json"), operation.at("/requestBody/content").propertyNames());
		assertEquals("#/components/schemas/" + (action.equals("signup") ? "SignupRequest" : "LoginRequest"),
			operation.at("/requestBody/content/application~1json/schema/$ref").asText());
		assertEquals(JSON.readTree(action.equals("signup")
			? "{\"email\":\"member@example.com\",\"password\":\"<password-at-least-8-chars>\",\"nickname\":\"단디\"}"
			: "{\"email\":\"member@example.com\",\"password\":\"<password>\"}"),
			operation.at("/requestBody/content/application~1json/example"));
		var responses = operation.path("responses");
		assertEquals(action.equals("signup") ? Set.of("201", "400", "401", "409", "415", "500")
			: Set.of("200", "400", "401", "415", "500"), responses.propertyNames());
		for (String status : responses.propertyNames()) {
			assertEquals(Set.of("application/json"), responses.path(status).path("content").propertyNames());
			var content = responses.path(status).at("/content/application~1json");
			assertEquals("#/components/schemas/" + (status.startsWith("2") ? "AuthResponse" : "ErrorResponse"),
				content.at("/schema/$ref").asText());
			assertEquals(JSON.readTree(status.startsWith("2")
				? "{\"accessToken\":\"<access-token>\",\"userId\":1,\"nickname\":\"단디\"}" : ERRORS.get(status)),
				content.path("example"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"SignupRequest", "LoginRequest"})
	void requestSchemasExposeFieldConstraintsAndWriteOnlyPasswords(String name) {
		var schema = spec.at("/components/schemas/" + name);
		var fields = schema.path("properties");
		assertEquals(name.equals("SignupRequest") ? Set.of("email", "password", "nickname")
			: Set.of("email", "password"), fields.propertyNames());
		assertEquals(fields.size(), schema.path("required").size());
		for (var required : schema.path("required")) assertTrue(fields.has(required.asText()));
		assertEquals("email", fields.at("/email/format").asText());
		assertEquals(255, fields.at("/email/maxLength").asInt());
		assertEquals("password", fields.at("/password/format").asText());
		assertTrue(fields.at("/password/writeOnly").asBoolean());
		assertNotEquals(72, fields.at("/password/maxLength").asInt());
		if (name.equals("SignupRequest")) {
			assertEquals(8, fields.at("/password/minLength").asInt());
			assertEquals(50, fields.at("/nickname/maxLength").asInt());
		} else {
			assertTrue(fields.at("/password/minLength").asInt() <= 1);
		}
	}

	@Test
	void authResponseSchemasExposeOnlyPublicFieldsAndGlobalBearerRemainsAvailable() {
		assertTrue(spec.path("paths").propertyNames().containsAll(Set.of("/api/auth/signup", "/api/auth/login")));
		assertTrue(spec.at("/components/schemas").propertyNames()
			.containsAll(Set.of("SignupRequest", "LoginRequest", "AuthResponse", "ErrorResponse")));
		for (String name : Set.of("AuthResponse", "ErrorResponse")) {
			var schema = spec.at("/components/schemas/" + name);
			var expected = name.equals("AuthResponse") ? Set.of("accessToken", "userId", "nickname") : Set.of("code", "message");
			assertEquals(expected, schema.path("properties").propertyNames());
			assertEquals(expected.size(), schema.path("required").size());
			for (var required : schema.path("required")) assertTrue(expected.contains(required.asText()));
		}
		assertEquals("integer", spec.at("/components/schemas/AuthResponse/properties/userId/type").asText());
		assertEquals("int64", spec.at("/components/schemas/AuthResponse/properties/userId/format").asText());
		assertFalse(spec.toString().contains("passwordHash"));
		assertFalse(spec.toString().contains("eyJ"));
		assertEquals(JSON.readTree("[{\"bearerAuth\":[]}]"), spec.path("security"));
		assertEquals(JSON.readTree("{\"bearerAuth\":{\"type\":\"http\",\"scheme\":\"bearer\",\"bearerFormat\":\"JWT\"}}"),
			spec.at("/components/securitySchemes"));
	}

	@Test
	void signupLoginAndDuplicateResponsesMatchPublishedContract() throws Exception {
		String email = UUID.randomUUID() + "@example.com";
		String body = JSON.writeValueAsString(Map.of("email", email, "password", "example-only-password", "nickname", "단디"));
		var signup = post("signup", body);
		assertEquals(201, signup.statusCode());
		var registered = assertAuthResponse(signup);
		var login = post("login", JSON.writeValueAsString(Map.of("email", email, "password", "example-only-password")));
		assertEquals(200, login.statusCode());
		var loggedIn = assertAuthResponse(login);
		assertEquals(registered.path("userId"), loggedIn.path("userId"));
		assertEquals(registered.path("nickname"), loggedIn.path("nickname"));
		assertError(post("signup", body), "signup", "409");
	}

	@ParameterizedTest
	@ValueSource(strings = {"signup", "login"})
	void malformedAndMissingInputMatchDocumented400(String action) throws Exception {
		assertError(post(action, "{"), action, "400");
		assertError(post(action, "{}"), action, "400");
	}

	@ParameterizedTest
	@ValueSource(strings = {"signup", "login"})
	void unsupportedContentTypeMatchesDocumented415(String action) throws Exception {
		var response = send(HttpRequest.newBuilder(uri("/api/auth/" + action)).header("Content-Type", "text/plain")
			.POST(HttpRequest.BodyPublishers.ofString("{}")));
		assertError(response, action, "415");
	}

	@ParameterizedTest
	@ValueSource(strings = {"signup", "login"})
	void malformedBearerMatchesDocumented401(String action) throws Exception {
		var response = send(HttpRequest.newBuilder(uri("/api/auth/" + action)).header("Content-Type", "application/json")
			.header("Authorization", "Bearer malformed").POST(HttpRequest.BodyPublishers.ofString("{}")));
		assertError(response, action, "401");
	}

	@Test
	void nonexistentCredentialsAndProtectedUserRouteRemainUnauthorized() throws Exception {
		assertError(post("login", JSON.writeValueAsString(Map.of("email", UUID.randomUUID() + "@example.com",
			"password", "x"))), "login", "401");
		var response = send(HttpRequest.newBuilder(uri("/api/users/me")).GET());
		assertEquals(401, response.statusCode());
		assertEquals(JSON.readTree(ERRORS.get("401")), JSON.readTree(response.body()));
	}

	private JsonNode assertAuthResponse(HttpResponse<String> response) {
		var body = JSON.readTree(response.body());
		assertEquals(spec.at("/components/schemas/AuthResponse/properties").propertyNames(), body.propertyNames());
		assertTrue(body.path("userId").isIntegralNumber());
		assertTrue(body.path("userId").asLong() > 0);
		assertEquals("단디", body.path("nickname").asText());
		assertEquals(3, body.path("accessToken").asText().split("\\.").length);
		return body;
	}

	private void assertError(HttpResponse<String> response, String action, String status) {
		assertEquals(Integer.parseInt(status), response.statusCode());
		assertEquals(spec.path("paths").path("/api/auth/" + action).path("post").path("responses").path(status)
			.at("/content/application~1json/example"), JSON.readTree(response.body()));
	}

	private HttpResponse<String> post(String action, String body) throws Exception {
		return send(HttpRequest.newBuilder(uri("/api/auth/" + action)).header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body)));
	}

	private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

	private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
		var request = builder.timeout(Duration.ofSeconds(10)).build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofString());
		String contentType = response.headers().firstValue("content-type").orElse("");
		Files.writeString(EVIDENCE.resolve("http-matrix.tsv"), request.method() + "\t" + request.uri().getPath()
			+ "\t" + response.statusCode() + "\t" + contentType + "\t" + response.body().length() + "\n", StandardOpenOption.APPEND);
		assertTrue(contentType.startsWith("application/json"));
		assertTrue(response.headers().firstValue("set-cookie").isEmpty());
		return response;
	}

	@AfterAll
	void recordServerPortForContextTeardownVerification() throws Exception {
		Files.writeString(EVIDENCE.resolve("server-port.txt"), Integer.toString(port));
	}
}
