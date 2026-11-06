package com.danditon.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordSecurityConfigurationTests {

	private AnnotationConfigApplicationContext context;
	private PasswordEncoder encoder;

	@BeforeEach
	void startContext() {
		context = new AnnotationConfigApplicationContext(PasswordSecurityConfiguration.class);
		encoder = context.getBean(PasswordEncoder.class);
	}

	@AfterEach
	void closeContext() {
		context.close();
	}

	@Test
	void encodingUsesBcryptIdentifierAndNeverStoresPlaintext() {
		String raw = "test-password";

		String encoded = encoder.encode(raw);

		assertTrue(encoded.startsWith("{bcrypt}"));
		assertFalse(raw.equals(encoded));
	}

	@Test
	void repeatedEncodingUsesIndependentSalts() {
		String raw = "test-password";

		String first = encoder.encode(raw);
		String second = encoder.encode(raw);

		assertFalse(first.equals(second));
		assertTrue(encoder.matches(raw, first));
		assertTrue(encoder.matches(raw, second));
	}

	@Test
	void onlyCorrectPasswordMatches() {
		String raw = "test-password";
		String encoded = encoder.encode(raw);

		boolean correctMatches = encoder.matches(raw, encoded);
		boolean wrongMatches = encoder.matches("different-password", encoded);

		assertTrue(correctMatches);
		assertFalse(wrongMatches);
	}

	@ParameterizedTest(name = "preserves surrounding spaces case {index}")
	@ValueSource(strings = {" test-password", "test-password ", " test-password "})
	void surroundingSpacesArePartOfPassword(String raw) {
		String encoded = encoder.encode(raw);

		boolean exactMatches = encoder.matches(raw, encoded);
		boolean trimmedMatches = encoder.matches(raw.trim(), encoded);

		assertTrue(exactMatches);
		assertFalse(trimmedMatches);
	}

	@Test
	void emptyInputDoesNotMatchUnderDefaultEncoderPolicy() {
		String encoded = encoder.encode("");

		boolean emptyMatches = encoder.matches("", encoded);

		assertTrue(encoded.startsWith("{bcrypt}"));
		assertFalse(emptyMatches);
	}

	@ParameterizedTest(name = "whitespace input case {index}")
	@ValueSource(strings = {" ", "\t"})
	void whitespaceInputsAreEncodedLiterally(String raw) {
		String encoded = encoder.encode(raw);

		boolean exactMatches = encoder.matches(raw, encoded);
		boolean differentMatches = encoder.matches(raw + "x", encoded);

		assertTrue(encoded.startsWith("{bcrypt}"));
		assertTrue(exactMatches);
		assertFalse(differentMatches);
	}
}
