package com.danditon;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import com.danditon.security.JwtSecurityConfiguration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(SecurityIntegrationTests.Fixtures.class)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityIntegrationTests {

	private static final byte[] KEY = "integration-only-32-byte-secret!!".getBytes(StandardCharsets.UTF_8);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Path EVIDENCE = Path.of(".omo/evidence/jwt-cors-security");
	private static final String UNAUTHORIZED = "{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요해요\"}";
	private static final String ALLOWED_ORIGIN = "https://marshrnello.cloud";
	@LocalServerPort private int port;
	private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager())
		.connectTimeout(Duration.ofSeconds(5)).build();

	@ParameterizedTest(name = "invalid key case {index}")
	@ValueSource(strings = {"", "   ", "not-standard-base64!", "c2hvcnQ=", "___________________________________________="})
	void invalidKeyPreventsContextStartupWithoutLeakingValue(String value) {
		new ApplicationContextRunner().withUserConfiguration(JwtSecurityConfiguration.class)
			.withPropertyValues("security.jwt.secret-base64=" + value).run(application -> {
				assertNotNull(application.getStartupFailure());
				Throwable cause = application.getStartupFailure();
				while (cause.getCause() != null) cause = cause.getCause();
				assertEquals("JWT_SECRET_BASE64 must be standard Base64 encoding at least 32 bytes", cause.getMessage());
				assertInstanceOf(IllegalArgumentException.class, cause);
			});
	}

	@Test
	void missingKeyPreventsContextStartup() {
		new ApplicationContextRunner().withUserConfiguration(JwtSecurityConfiguration.class).run(application -> {
			assertNotNull(application.getStartupFailure());
		});
	}

	@Test
	void bearerSubjectIsPrincipalAndAuthenticationDoesNotPersist() throws Exception {
		var response = send(request("GET", "/security/principal").header("Authorization", "Bearer " + valid()));
		assertEquals(200, response.statusCode());
		assertEquals("test-subject", response.body());
		assertTrue(response.headers().firstValue("set-cookie").isEmpty());
		unauthorized(send(request("GET", "/security/principal")));
	}

	@Test
	void authenticatedPostNeedsNoCsrfToken() throws Exception {
		assertEquals(200, send(request("POST", "/security/principal")
			.header("Authorization", "Bearer " + valid())).statusCode());
	}

	@Test
	void issuerAudienceAndRoleClaimsDoNotCreateAdditionalPolicy() throws Exception {
		String token = sign("HS256", "{\"sub\":\"test-subject\",\"exp\":"
			+ (Instant.now().getEpochSecond() + 300)
			+ ",\"iss\":\"fixture-issuer\",\"aud\":\"fixture-audience\",\"scope\":\"admin\",\"roles\":[\"ADMIN\"]}", KEY);
		var response = send(request("GET", "/security/authorities").header("Authorization", "Bearer " + token));
		assertEquals(200, response.statusCode());
		assertFalse(response.body().contains("SCOPE_"));
		assertFalse(response.body().contains("ROLE_"));
		assertFalse(response.body().contains("ADMIN"));
	}

	static Stream<String> invalidTokens() throws Exception {
		long now = Instant.now().getEpochSecond();
		return Stream.of("malformed", valid() + "tampered",
			sign("HS256", "{\"sub\":\"test-subject\",\"exp\":" + (now + 300) + "}", new byte[32]),
			token("\"test-subject\"", now - 300, null), token("\"test-subject\"", now + 600, now + 300),
			sign("HS256", "{\"sub\":\"test-subject\"}", KEY), token(null, now + 300, null),
			token("\"\"", now + 300, null), token("\"   \"", now + 300, null),
			token("42", now + 300, null), token("[]", now + 300, null),
			sign("none", "{\"sub\":\"test-subject\",\"exp\":" + (now + 300) + "}", KEY),
			sign("HS384", "{\"sub\":\"test-subject\",\"exp\":" + (now + 300) + "}", KEY));
	}

	@ParameterizedTest(name = "invalid bearer case {index}")
	@MethodSource("invalidTokens")
	void invalidBearerHasGenericJson401(String token) throws Exception {
		unauthorized(send(request("GET", "/security/principal").header("Authorization", "Bearer " + token)));
	}

	@Test
	void timestampsWithinSixtySecondToleranceAreAccepted() throws Exception {
		long now = Instant.now().getEpochSecond();
		assertEquals(200, send(request("GET", "/security/principal")
			.header("Authorization", "Bearer " + token("\"test-subject\"", now - 20, now - 40))).statusCode());
		assertEquals(200, send(request("GET", "/security/principal")
			.header("Authorization", "Bearer " + token("\"test-subject\"", now + 300, now + 20))).statusCode());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/auth/signup", "/api/auth/login"})
	void onlyExactPostAuthRoutesAreAnonymous(String path) throws Exception {
		var empty = send(request("POST", path));
		assertEquals(400, empty.statusCode());
		assertEquals("{\"code\":\"INVALID_INPUT\",\"message\":\"입력값이 올바르지 않아요\"}", empty.body());
		assertEquals(401, send(request("HEAD", path)).statusCode());
		for (String method : new String[]{"GET", "PUT", "PATCH", "DELETE", "OPTIONS"}) {
			unauthorized(send(request(method, path)));
		}
		for (String suffix : new String[]{"/", "/extra"}) {
			unauthorized(send(request("POST", path + suffix)));
		}
		assertEquals(400, send(request("POST", path).header("Authorization", "Bearer malformed")).statusCode());
	}

	static Stream<String> ignoredBearerTokens() throws Exception {
		return Stream.of("malformed", "invalid token!",
			token("\"test-subject\"", Instant.now().getEpochSecond() - 600, null));
	}

	@ParameterizedTest(name = "exact bearer boundary {index}")
	@MethodSource("ignoredBearerTokens")
	void ignoredAuthBearerStillRejectedOutsideExactPost(String token) throws Exception {
		String authorization = "Bearer " + token;
		for (String path : new String[]{"/api/auth/signup", "/api/auth/login"}) {
			assertEquals(401, send(request("HEAD", path).header("Authorization", authorization)).statusCode());
			for (String method : new String[]{"GET", "PUT", "PATCH", "DELETE", "OPTIONS"}) {
				unauthorized(send(request(method, path).header("Authorization", authorization)));
			}
			for (String suffix : new String[]{"/", "/extra"}) {
				unauthorized(send(request("POST", path + suffix).header("Authorization", authorization)));
			}
			var preflight = send(request("OPTIONS", path).header("Origin", ALLOWED_ORIGIN)
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "Authorization,Content-Type"));
			assertEquals(200, preflight.statusCode());
			assertCors(preflight);
		}
		unauthorized(send(request("GET", "/security/principal").header("Authorization", authorization)));
		unauthorized(send(request("GET", "/v3/api-docs").header("Authorization", authorization)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs",
		"/v3/api-docs.yaml", "/v3/api-docs/swagger-config"})
	void swaggerAndOpenApiRoutesAreUnavailable(String path) throws Exception {
		unauthorized(send(request("GET", path)));
		assertEquals(404, send(request("GET", path).header("Authorization", "Bearer " + valid())).statusCode());
	}

	@Test
	void basicCookiesQueryAndFormCannotAuthenticate() throws Exception {
		unauthorized(send(request("GET", "/security/principal").header("Authorization", "Basic Zm9vOmJhcg==")));
		unauthorized(send(request("GET", "/security/principal").header("Cookie", "JSESSIONID=stale; access_token=" + valid())));
		unauthorized(send(request("GET", "/security/principal?access_token=" + valid())));
		unauthorized(send(request("POST", "/security/principal").header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString("access_token=" + valid()))));
		unauthorized(send(request("GET", "/login")));
		unauthorized(send(request("POST", "/login").header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString("username=user&password=fixture"))));
	}

	@Test
	void corsAllowsBearerRequestsFromConfiguredOriginWithoutCredentials() throws Exception {
		for (String method : new String[]{"POST", "PATCH"}) {
			var response = send(request("OPTIONS", "/security/principal").header("Origin", ALLOWED_ORIGIN)
				.header("Access-Control-Request-Method", method)
				.header("Access-Control-Request-Headers", "Authorization,Content-Type"));
			assertEquals(200, response.statusCode());
			assertCors(response);
			assertEquals("GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS", response.headers().firstValue("access-control-allow-methods").orElseThrow());
			assertEquals("Authorization, Content-Type", response.headers().firstValue("access-control-allow-headers").orElseThrow());
		}
		var accepted = send(request("GET", "/security/principal").header("Origin", ALLOWED_ORIGIN).header("Authorization", "Bearer " + valid()));
		assertEquals(200, accepted.statusCode());
		assertCors(accepted);
		var rejected = send(request("GET", "/security/principal").header("Origin", ALLOWED_ORIGIN));
		unauthorized(rejected);
		assertCors(rejected);
	}

	@Test
	void corsRejectsPreflightFromUnconfiguredOrigin() throws Exception {
		var response = send(request("OPTIONS", "/security/principal").header("Origin", "https://untrusted.example")
			.header("Access-Control-Request-Method", "POST")
			.header("Access-Control-Request-Headers", "Authorization,Content-Type"));
		assertEquals(403, response.statusCode());
		assertTrue(response.headers().firstValue("access-control-allow-origin").isEmpty());
	}

	@Test
	void unlistedCorsHeaderAndBareOptionsAreRejected() throws Exception {
		var response = send(request("OPTIONS", "/security/principal").header("Origin", ALLOWED_ORIGIN)
			.header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "X-Unlisted"));
		assertEquals(403, response.statusCode());
		assertTrue(response.headers().firstValue("access-control-allow-origin").isEmpty());
		unauthorized(send(request("OPTIONS", "/security/principal")));
	}

	@Test
	void downstreamAccessDeniedUsesProductionJsonHandler() throws Exception {
		var response = send(request("GET", "/security/denied").header("Authorization", "Bearer " + valid()));
		assertEquals(403, response.statusCode());
		assertEquals("{\"code\":\"FORBIDDEN\",\"message\":\"권한이 없어요\"}", response.body());
		assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/json"));
	}

	private void assertCors(HttpResponse<String> response) {
		assertEquals(ALLOWED_ORIGIN, response.headers().firstValue("access-control-allow-origin").orElseThrow());
		assertTrue(response.headers().firstValue("access-control-allow-credentials").isEmpty());
	}

	private void unauthorized(HttpResponse<String> response) {
		assertEquals(401, response.statusCode());
		assertEquals(UNAUTHORIZED, response.body());
		assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/json"));
		assertTrue(response.headers().firstValue("set-cookie").isEmpty());
	}

	private HttpRequest.Builder request(String method, String path) {
		return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody());
	}

	private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
		var request = builder.header("Accept", "application/json").build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofString());
		var headers = new java.util.TreeMap<>(response.headers().map());
		headers.remove("set-cookie");
		record("HTTP_QA " + request.method() + " " + request.uri().getPath() + " status="
			+ response.statusCode() + " headers=" + headers + " body=" + response.body()
				.replaceAll("(\"accessToken\"\\s*:\\s*\")[^\"]*(\")", "$1[REDACTED]$2"));
		return response;
	}

	private static String valid() throws Exception {
		return token("\"test-subject\"", Instant.now().getEpochSecond() + 300, null);
	}

	private static String token(String subject, long expiration, Long notBefore) throws Exception {
		return sign("HS256", "{" + (subject == null ? "" : "\"sub\":" + subject + ",")
			+ "\"exp\":" + expiration + (notBefore == null ? "" : ",\"nbf\":" + notBefore) + "}", KEY);
	}

	private static String sign(String algorithm, String claims, byte[] key) throws Exception {
		var encoder = Base64.getUrlEncoder().withoutPadding();
		String input = encoder.encodeToString(JSON.writeValueAsBytes(Map.of("alg", algorithm))) + "."
			+ encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
		if (algorithm.equals("none")) return input + ".";
		var mac = Mac.getInstance(algorithm.equals("HS384") ? "HmacSHA384" : "HmacSHA256");
		mac.init(new SecretKeySpec(key, mac.getAlgorithm()));
		return input + "." + encoder.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
	}

	private static synchronized void record(String line) throws IOException {
		Files.createDirectories(EVIDENCE);
		Files.writeString(EVIDENCE.resolve("task-1-http.txt"), line + System.lineSeparator(),
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		System.out.println(line);
	}

	@AfterAll
	void recordContextTeardownPort() throws Exception {
		record("TEARDOWN port=" + port + " contextClosure=DirtiesContext");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Fixtures {
		@Bean ProbeController probeController() { return new ProbeController(); }
		@Bean FilterRegistrationBean<jakarta.servlet.Filter> deniedFilter() {
			var registration = new FilterRegistrationBean<jakarta.servlet.Filter>((request, response, chain) -> {
				throw new AccessDeniedException("fixture denial");
			});
			registration.addUrlPatterns("/security/denied");
			registration.setOrder(Ordered.LOWEST_PRECEDENCE);
			return registration;
		}
	}

	@RestController
	static class ProbeController {
		@RequestMapping("/security/principal") String principal(Principal principal) { return principal.getName(); }
		@RequestMapping("/security/authorities") String authorities(Authentication auth) { return auth.getAuthorities().toString(); }
	}
}
