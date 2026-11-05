package com.danditon.security;

import java.text.ParseException;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
public class JwtSecurityConfiguration {

	@Bean
	JwtDecoder jwtDecoder(@Value("${security.jwt.secret-base64:}") String encoded) {
		byte[] key;
		try {
			key = Base64.getDecoder().decode(encoded);
		} catch (IllegalArgumentException exception) {
			throw invalidKey();
		}
		if (key.length < 32) {
			throw invalidKey();
		}
		var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
			.macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
			new JwtTimestampValidator(Duration.ofSeconds(60)), jwt -> {
				Object subject = jwt.getClaims().get("sub");
				return jwt.getExpiresAt() != null && subject instanceof String text && !text.isBlank()
					? OAuth2TokenValidatorResult.success()
					: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
			}));
		return token -> {
			try {
				// Nimbus coerces numeric subjects to strings when creating JWTClaimsSet.
				var claims = SignedJWT.parse(token).getPayload().toJSONObject();
				if (claims == null || !(claims.get("sub") instanceof String)) {
					throw new BadJwtException("Invalid JWT subject");
				}
			} catch (ParseException exception) {
				throw new BadJwtException("Invalid JWT");
			}
			return decoder.decode(token);
		};
	}

	private static IllegalArgumentException invalidKey() {
		return new IllegalArgumentException("JWT_SECRET_BASE64 must be standard Base64 encoding at least 32 bytes");
	}
}
