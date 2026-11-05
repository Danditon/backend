package com.danditon.security;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

	private static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofHours(24);

	private final JwtEncoder encoder;
	private final Clock clock;

	public JwtTokenService(JwtEncoder encoder, Clock clock) {
		this.encoder = encoder;
		this.clock = clock;
	}

	public String issueAccessToken(Long userId) {
		if (userId == null || userId <= 0) {
			throw new IllegalArgumentException("User ID must be positive");
		}
		var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		var claims = JwtClaimsSet.builder()
			.subject(userId.toString())
			.issuedAt(now)
			.expiresAt(now.plus(ACCESS_TOKEN_LIFETIME))
			.build();
		var header = JwsHeader.with(MacAlgorithm.HS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}
}
