package com.danditon.auth.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.danditon.auth.validation.PasswordByteLength;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import tools.jackson.databind.json.JsonMapper;

class AuthRequestValidationTests {
	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private static final Validator VALIDATOR = FACTORY.getValidator();
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String PASSWORD_SENTINEL = "fixture-Secret-Sentinel-47";

	@AfterAll
	static void closeValidator() {
		FACTORY.close();
	}

	@Test
	void signupNormalizesEmailAndNicknameWhilePreservingPasswordWhitespace() {
		String password = "  " + PASSWORD_SENTINEL + "  ";

		var request = new SignupRequest("  I.USER@EXAMPLE.COM  ", password, "  단디  ");

		assertEquals("i.user@example.com", request.email());
		assertEquals("단디", request.nickname());
		assertTrue(password.equals(request.password()), "password whitespace must be preserved");
		assertTrue(VALIDATOR.validate(request).isEmpty());
	}

	@Test
	void loginNormalizesEmailWhilePreservingPasswordWhitespace() {
		String password = "  " + PASSWORD_SENTINEL + "  ";

		var request = new LoginRequest("  I.USER@EXAMPLE.COM  ", password);

		assertEquals("i.user@example.com", request.email());
		assertTrue(password.equals(request.password()), "password whitespace must be preserved");
		assertTrue(VALIDATOR.validate(request).isEmpty());
	}

	static Stream<Arguments> passwordBoundaries() {
		return Stream.of(
			Arguments.of("a".repeat(72), true), Arguments.of("a".repeat(73), false),
			Arguments.of("한".repeat(24), true), Arguments.of("한".repeat(25), false),
			Arguments.of("😀".repeat(18), true), Arguments.of("😀".repeat(19), false));
	}

	@ParameterizedTest(name = "UTF-8 boundary case {index}")
	@MethodSource("passwordBoundaries")
	void utf8PasswordByteLimitAppliesToBothRequests(String password, boolean accepted) {
		var requests = List.of(new SignupRequest("user@example.com", password, "name"),
			new LoginRequest("user@example.com", password));

		var results = requests.stream().map(request -> VALIDATOR.validate(request).isEmpty()).toList();

		assertEquals(List.of(accepted, accepted), results);
	}

	@ParameterizedTest(name = "invalid email case {index}")
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "malformed", "user@@example.com"})
	void invalidEmailsAreRejectedByBothRequests(String email) {
		var requests = List.of(new SignupRequest(email, PASSWORD_SENTINEL, "name"),
			new LoginRequest(email, PASSWORD_SENTINEL));

		var fields = requests.stream().map(AuthRequestValidationTests::invalidFields).toList();

		assertEquals(List.of(Set.of("email"), Set.of("email")), fields);
	}

	@Test
	void emailLengthBoundaryAppliesToBothRequests() {
		String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62);
		var requests = List.of(new SignupRequest(email, PASSWORD_SENTINEL, "name"),
			new LoginRequest(email, PASSWORD_SENTINEL),
			new SignupRequest(email + "d", PASSWORD_SENTINEL, "name"),
			new LoginRequest(email + "d", PASSWORD_SENTINEL));

		var fields = requests.stream().map(AuthRequestValidationTests::invalidFields).toList();

		assertEquals(255, email.length());
		assertEquals(List.of(Set.of(), Set.of(), Set.of("email"), Set.of("email")), fields);
	}

	@ParameterizedTest(name = "blank password case {index}")
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "        "})
	void missingOrBlankPasswordsAreRejectedByBothRequests(String password) {
		var requests = List.of(new SignupRequest("user@example.com", password, "name"),
			new LoginRequest("user@example.com", password));

		var fields = requests.stream().map(AuthRequestValidationTests::invalidFields).toList();

		assertEquals(List.of(Set.of("password"), Set.of("password")), fields);
	}

	@Test
	void byteValidatorDefersNullToRequiredConstraint() {
		var request = new LoginRequest("user@example.com", null);

		var violations = VALIDATOR.validate(request);

		assertEquals(1, violations.size());
		assertFalse(violations.stream().anyMatch(violation -> violation.getConstraintDescriptor()
			.getAnnotation().annotationType().equals(PasswordByteLength.class)));
	}

	@ParameterizedTest(name = "signup minimum length case {index}")
	@ValueSource(ints = {1, 7, 8})
	void signupRequiresEightCharactersWhileLoginAllowsShortNonblankPassword(int length) {
		String password = "a".repeat(length);
		var requests = List.of(new SignupRequest("user@example.com", password, "name"),
			new LoginRequest("user@example.com", password));

		var results = requests.stream().map(request -> VALIDATOR.validate(request).isEmpty()).toList();

		assertEquals(List.of(length >= 8, true), results);
	}

	@ParameterizedTest(name = "invalid nickname case {index}")
	@NullAndEmptySource
	@ValueSource(strings = {"   "})
	void blankNicknamesAreRejected(String nickname) {
		var request = new SignupRequest("user@example.com", PASSWORD_SENTINEL, nickname);

		var fields = invalidFields(request);

		assertEquals(Set.of("nickname"), fields);
	}

	@ParameterizedTest(name = "nickname length case {index}")
	@ValueSource(ints = {50, 51})
	void nicknameLengthBoundaryIsEnforcedAfterTrim(int length) {
		var request = new SignupRequest("user@example.com", PASSWORD_SENTINEL, " " + "한".repeat(length) + " ");

		boolean accepted = VALIDATOR.validate(request).isEmpty();

		assertEquals(length <= 50, accepted);
	}

	@Test
	void passwordsAreWriteOnlyForJsonAndExcludedFromToString() {
		String input = JSON.writeValueAsString(Map.of("email", " USER@EXAMPLE.COM ",
			"password", PASSWORD_SENTINEL, "nickname", " name "));
		String loginInput = JSON.writeValueAsString(Map.of("email", " USER@EXAMPLE.COM ",
			"password", PASSWORD_SENTINEL));

		var signup = JSON.readValue(input, SignupRequest.class);
		var login = JSON.readValue(loginInput, LoginRequest.class);

		for (Object request : List.of(signup, login)) {
			String serialized = JSON.writeValueAsString(request);
			assertFalse(serialized.contains(PASSWORD_SENTINEL), "serialized request must omit secret");
			assertFalse(JSON.readTree(serialized).has("password"));
			assertFalse(request.toString().contains(PASSWORD_SENTINEL), "request string must omit secret");
		}
		assertTrue(PASSWORD_SENTINEL.equals(signup.password()), "signup must deserialize secret");
		assertTrue(PASSWORD_SENTINEL.equals(login.password()), "login must deserialize secret");
		assertEquals("user@example.com", signup.email());
		assertEquals("user@example.com", login.email());
		assertEquals("name", signup.nickname());
	}

	@Test
	void authResponseHasExactlyThePublicContractKeys() {
		var response = new AuthResponse("fixture-token", 42L, "단디");

		var json = JSON.readTree(JSON.writeValueAsString(response));

		assertEquals(3, json.size());
		assertEquals("fixture-token", json.get("accessToken").asString());
		assertEquals(42L, json.get("userId").asLong());
		assertEquals("단디", json.get("nickname").asString());
	}

	private static Set<String> invalidFields(Object request) {
		return VALIDATOR.validate(request).stream()
			.map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
	}
}
