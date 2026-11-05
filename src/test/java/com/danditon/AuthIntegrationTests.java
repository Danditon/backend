package com.danditon;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.danditon.user.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:auth-http-${random.uuid};DB_CLOSE_DELAY=0",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@Import(SecurityIntegrationTests.Fixtures.class)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class AuthIntegrationTests {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String PASSWORD = "  HTTP-secret-sentinel-7  ";
	private static final String INVALID = "{\"code\":\"INVALID_INPUT\",\"message\":\"입력값이 올바르지 않아요\"}";
	private static final String UNAUTHORIZED = "{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요해요\"}";
	@LocalServerPort private int port;
	@Autowired private UserRepository users;
	@Autowired private PasswordEncoder passwords;
	private final CookieManager cookies = new CookieManager();
	private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
		.connectTimeout(Duration.ofSeconds(5)).build();

	@Test
	void signupAndLoginReturnExactContractAndPersistedPrincipalWithoutSession() throws Exception {
		String email = uniqueEmail();
		var signup = post("signup", JSON.writeValueAsString(Map.of(
			"email", " " + email.toUpperCase(java.util.Locale.ROOT) + " ",
			"password", PASSWORD, "nickname", " name ")));
		var created = authResponse(signup, 201);
		assertTrue(signup.headers().firstValue("location").isEmpty());
		long id = created.get("userId").asLong();
		var stored = users.findById(id).orElseThrow();
		assertEquals(email, stored.getEmail());
		assertEquals("name", stored.getNickname());
		assertTrue(stored.getPasswordHash().startsWith("{bcrypt}"));
		assertTrue(passwords.matches(PASSWORD, stored.getPasswordHash()));
		assertFalse(passwords.matches(PASSWORD.trim(), stored.getPasswordHash()));
		var login = authResponse(post("login", credentials(email, PASSWORD)), 200);
		assertEquals(id, login.get("userId").asLong());
		for (var body : new JsonNode[]{created, login}) {
			var principal = send(HttpRequest.newBuilder(uri("/security/principal"))
				.header("Authorization", "Bearer " + body.get("accessToken").asText()).GET());
			assertEquals(200, principal.statusCode());
			assertEquals(Long.toString(id), principal.body());
			assertNoCookie(principal);
			assertError(send(HttpRequest.newBuilder(uri("/security/principal")).GET()), 401, UNAUTHORIZED);
		}
		assertTrue(cookies.getCookieStore().getCookies().isEmpty());
		System.out.println("AUTH_PERSISTENCE row_found=true canonical_email=true bcrypt=true exact_password=true principal_decimal_id=true cookies=0");
	}

	@Test
	void duplicateAndBadCredentialsHaveExactGenericErrors() throws Exception {
		String email = uniqueEmail();
		String signup = JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD, "nickname", "name"));
		authResponse(post("signup", signup), 201);
		long before = users.count();
		assertError(post("signup", JSON.writeValueAsString(Map.of("email", " " + email.toUpperCase(java.util.Locale.ROOT) + " ",
			"password", PASSWORD, "nickname", "other"))), 409,
			"{\"code\":\"DUPLICATE_EMAIL\",\"message\":\"이미 가입된 이메일이에요\"}");
		assertEquals(before, users.count());
		var wrong = post("login", credentials(email, "wrong-input"));
		var missing = post("login", credentials(uniqueEmail(), "wrong-input"));
		assertError(wrong, 401, UNAUTHORIZED);
		assertError(missing, 401, UNAUTHORIZED);
		assertEquals(wrong.body(), missing.body());
		assertError(post("login", credentials(email, "short")), 401, UNAUTHORIZED);
	}

	static Stream<Arguments> invalidInputs() {
		var cases = new ArrayList<Arguments>();
		for (String endpoint : new String[]{"signup", "login"}) {
			String[] bodies = {"", "{", "null", "[]", "42", "\"text\"", "{}"};
			for (int i = 0; i < bodies.length; i++) cases.add(Arguments.of(endpoint, "body-" + i, bodies[i]));
			for (String field : endpoint.equals("signup") ? new String[]{"email", "password", "nickname"} : new String[]{"email", "password"}) {
				var valid = new HashMap<String, Object>(Map.of("email", "valid@example.com", "password", PASSWORD, "nickname", "name"));
				valid.remove(field);
				cases.add(Arguments.of(endpoint, field + "-missing", JSON.writeValueAsString(valid)));
				Object[] invalid = {null, "", "   ", Map.of("invalid", true), new String[]{"invalid"}};
				for (int i = 0; i < invalid.length; i++) {
					valid.put(field, invalid[i]);
					cases.add(Arguments.of(endpoint, field + "-invalid-" + i, JSON.writeValueAsString(valid)));
				}
			}
			cases.add(Arguments.of(endpoint, "email-format", credentials("invalid", PASSWORD)));
			cases.add(Arguments.of(endpoint, "email-over255", credentials("a".repeat(244) + "@example.com", PASSWORD)));
			cases.add(Arguments.of(endpoint, "password-over72", credentials("valid@example.com", "a".repeat(73))));
			cases.add(Arguments.of(endpoint, "password-multibyte-over72", credentials("valid@example.com", "가".repeat(25))));
		}
		cases.add(Arguments.of("signup", "password-under8", JSON.writeValueAsString(Map.of("email", "valid@example.com", "password", "1234567", "nickname", "name"))));
		cases.add(Arguments.of("signup", "nickname-over50", JSON.writeValueAsString(Map.of("email", "valid@example.com", "password", PASSWORD, "nickname", "n".repeat(51)))));
		return cases.stream();
	}

	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("invalidInputs")
	void malformedInputIsExact400AndCreatesNoUser(String endpoint, String scenario, String body) throws Exception {
		long before = users.count();
		assertError(post(endpoint, body), 400, INVALID);
		assertEquals(before, users.count());
		System.out.println("AUTH_INVALID scenario=" + endpoint + "/" + scenario + " status=400 rows_unchanged=true");
	}

	@Test
	void maximumBoundariesAreAccepted() throws Exception {
		String email = uniqueEmail();
		String password = "가".repeat(24);
		var response = post("signup", JSON.writeValueAsString(Map.of("email", email, "password", password, "nickname", "n".repeat(50))));
		assertEquals(201, response.statusCode());
		assertEquals(200, post("login", credentials(email, password)).statusCode());
	}

	private JsonNode authResponse(HttpResponse<String> response, int status) {
		assertEquals(status, response.statusCode());
		assertNoCookie(response);
		var body = JSON.readTree(response.body());
		assertEquals(Set.of("accessToken", "userId", "nickname"), body.propertyNames());
		assertTrue(body.get("accessToken").isString());
		assertFalse(body.get("accessToken").asText().isBlank());
		assertTrue(body.get("userId").isIntegralNumber());
		assertTrue(body.get("userId").asLong() > 0);
		assertEquals("name", body.get("nickname").asText());
		return body;
	}

	private void assertError(HttpResponse<String> response, int status, String expected) {
		assertEquals(status, response.statusCode());
		assertEquals(expected, response.body());
		assertNoCookie(response);
	}

	private void assertNoCookie(HttpResponse<String> response) {
		assertTrue(response.headers().firstValue("set-cookie").isEmpty());
	}

	private static String credentials(String email, String password) {
		return JSON.writeValueAsString(Map.of("email", email, "password", password, "nickname", "name"));
	}

	private static String uniqueEmail() { return UUID.randomUUID() + "@example.com"; }
	private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

	private HttpResponse<String> post(String endpoint, String body) throws Exception {
		return send(HttpRequest.newBuilder(uri("/api/auth/" + endpoint)).header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body)));
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		var built = request.timeout(Duration.ofSeconds(10)).header("Accept", "application/json").build();
		var response = client.send(built, HttpResponse.BodyHandlers.ofString());
		assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/json"));
		String keys = response.body().startsWith("{") ? JSON.readTree(response.body()).propertyNames().toString() : "principal";
		System.out.println("AUTH_HTTP " + built.method() + " " + built.uri().getPath() + " status=" + response.statusCode() + " keys=" + keys);
		return response;
	}

	@AfterAll
	void teardown() {
		cookies.getCookieStore().removeAll();
		System.out.println("AUTH_TEARDOWN port=" + port + " contextClosure=DirtiesContext cookies=0");
	}
}
