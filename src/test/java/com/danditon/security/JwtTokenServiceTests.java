package com.danditon.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Set;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtTokenServiceTests {

	private static final String ENCODED_KEY = Base64.getEncoder().encodeToString(
		"integration-only-32-byte-secret!!".getBytes(StandardCharsets.UTF_8));
	private static final Clock FIXED_CLOCK = Clock.fixed(
		Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_789), ZoneOffset.UTC);
	private final JwtSecurityConfiguration configuration = new JwtSecurityConfiguration();
	private final JwtDecoder decoder = configuration.jwtDecoder(configuration.jwtSecretKey(ENCODED_KEY));
	private final JwtTokenService service = new JwtTokenService(
		configuration.jwtEncoder(configuration.jwtSecretKey(ENCODED_KEY)), FIXED_CLOCK);

	@Test
	void issuesHs256TokenWithOnlyStringSubjectAndSecondPrecisionTwentyFourHourTimestamps() throws Exception {
		String token = service.issueAccessToken(42L);
		var decoded = decoder.decode(token);
		var raw = SignedJWT.parse(token);
		var claims = raw.getPayload().toJSONObject();
		assertEquals("HS256", raw.getHeader().getAlgorithm().getName());
		assertEquals(Set.of("sub", "iat", "exp"), claims.keySet());
		assertInstanceOf(String.class, claims.get("sub"));
		assertEquals("42", claims.get("sub"));
		assertEquals("42", decoded.getSubject());
		assertEquals(FIXED_CLOCK.instant().truncatedTo(ChronoUnit.SECONDS), decoded.getIssuedAt());
		assertEquals(decoded.getIssuedAt().plus(Duration.ofHours(24)), decoded.getExpiresAt());
		assertEquals(86_400L, Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()).getSeconds());
		assertEquals(decoded.getIssuedAt().getEpochSecond(), ((Number) claims.get("iat")).longValue());
		assertEquals(decoded.getExpiresAt().getEpochSecond(), ((Number) claims.get("exp")).longValue());
	}

	@Test
	void productionBeansIssueTokenAcceptedByProductionDecoder() {
		new ApplicationContextRunner().withUserConfiguration(JwtSecurityConfiguration.class, JwtTokenService.class)
			.withPropertyValues("security.jwt.secret-base64=" + ENCODED_KEY).run(application -> {
				assertNull(application.getStartupFailure());
				assertEquals(ZoneOffset.UTC, application.getBean(Clock.class).getZone());
				String token = application.getBean(JwtTokenService.class).issueAccessToken(42L);
				assertEquals("42", application.getBean(JwtDecoder.class).decode(token).getSubject());
			});
	}

	@Test
	void rejectsPayloadTamperingWithoutResigning() throws Exception {
		String token = service.issueAccessToken(42L);
		var claims = SignedJWT.parse(token).getPayload().toJSONObject();
		claims.put("sub", "43");
		String replacement = Base64.getUrlEncoder().withoutPadding().encodeToString(
			com.nimbusds.jose.util.JSONObjectUtils.toJSONString(claims).getBytes(StandardCharsets.UTF_8));
		String[] parts = token.split("\\.");
		String tampered = parts[0] + "." + replacement + "." + parts[2];
		assertThrows(JwtException.class, () -> decoder.decode(tampered));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	void rejectsInvalidUserIds(Long userId) {
		assertThrows(IllegalArgumentException.class, () -> service.issueAccessToken(userId));
	}

	@Test
	void supportsLargestPositiveUserId() {
		assertEquals(Long.toString(Long.MAX_VALUE), decoder.decode(service.issueAccessToken(Long.MAX_VALUE)).getSubject());
	}

	@ParameterizedTest
	@ValueSource(strings = {"malformed-secret!", "c2hvcnQ=", "___________________________________________="})
	void invalidKeyHasExactSanitizedMessage(String encoded) {
		var exception = assertThrows(IllegalArgumentException.class, () -> configuration.jwtSecretKey(encoded));
		assertEquals("JWT_SECRET_BASE64 must be standard Base64 encoding at least 32 bytes", exception.getMessage());
		assertFalse(exception.getMessage().contains(encoded));
		assertNull(exception.getCause());
	}
}
