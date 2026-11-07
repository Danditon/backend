package com.danditon;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.danditon.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:user-http-${random.uuid};DB_CLOSE_DELAY=0",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@DirtiesContext
@Timeout(45)
class UserApiIntegrationTests {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String UNAUTHORIZED = "{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요해요\"}";
	@LocalServerPort private int port;
	@Autowired private UserRepository users;
	@Autowired private JwtEncoder encoder;
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@Test
	void profileReturnsOnlyStoredPublicFieldsForAuthenticatedUser() throws Exception {
		var signedUp = signup("profile-owner");
		var stored = users.findById(signedUp.get("userId").asLong()).orElseThrow();

		var response = get("", signedUp.get("accessToken").asText());

		assertEquals(200, response.statusCode());
		var body = JSON.readTree(response.body());
		assertEquals(Set.of("userId", "email", "nickname", "createdAt"), body.propertyNames());
		assertTrue(body.get("userId").isIntegralNumber());
		assertTrue(body.get("email").isString());
		assertTrue(body.get("nickname").isString());
		assertTrue(body.get("createdAt").isString());
		assertEquals(stored.getId(), body.get("userId").asLong());
		assertEquals(stored.getEmail(), body.get("email").asText());
		assertEquals(stored.getNickname(), body.get("nickname").asText());
		assertEquals(stored.getCreatedAt(), LocalDateTime.parse(body.get("createdAt").asText()));
		assertFalse(response.body().contains(stored.getPasswordHash()));
		assertFalse(response.body().contains(signedUp.get("accessToken").asText()));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void profileUsesEachTokenOwnerEvenWhenQuerySpoofsOtherUser(boolean useSecondUser) throws Exception {
		var first = signup("first-user");
		var second = signup("second-user");
		var owner = useSecondUser ? second : first;
		var other = useSecondUser ? first : second;
		var stored = users.findById(owner.get("userId").asLong()).orElseThrow();

		var response = get("?userId=" + other.get("userId").asLong() + "&email=spoof%40example.com",
			owner.get("accessToken").asText());

		assertEquals(200, response.statusCode());
		var body = JSON.readTree(response.body());
		assertEquals(stored.getId(), body.get("userId").asLong());
		assertEquals(stored.getEmail(), body.get("email").asText());
		assertEquals(stored.getNickname(), body.get("nickname").asText());
		assertNotEquals(other.get("userId").asLong(), body.get("userId").asLong());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "malformed"})
	void anonymousAndMalformedBearerHaveExactUnauthorizedResponse(String token) throws Exception {
		var response = get("", token);

		assertEquals(401, response.statusCode());
		assertEquals(UNAUTHORIZED, response.body());
	}

	@ParameterizedTest
	@ValueSource(strings = {"not-a-number", "9223372036854775808", "9223372036854775807"})
	void nonnumericOverflowAndNonexistentSubjectsHaveExactUnauthorizedResponse(String subject) throws Exception {
		var claims = JwtClaimsSet.builder().subject(subject)
			.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
		String token = encoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

		var response = get("", token);

		assertEquals(401, response.statusCode());
		assertEquals(UNAUTHORIZED, response.body());
	}

	private JsonNode signup(String nickname) throws Exception {
		String payload = JSON.writeValueAsString(Map.of("email", UUID.randomUUID() + "@example.com",
			"password", "profile-test-only-7", "nickname", nickname));
		var response = send(HttpRequest.newBuilder(uri("/api/auth/signup"))
			.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)));
		assertEquals(201, response.statusCode());
		return JSON.readTree(response.body());
	}

	private HttpResponse<String> get(String query, String token) throws Exception {
		var request = HttpRequest.newBuilder(uri("/api/users/me" + query)).GET();
		if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
		return send(request);
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		var response = client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
		assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/json"));
		assertTrue(response.headers().firstValue("set-cookie").isEmpty());
		return response;
	}

	private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
}
