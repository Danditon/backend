package com.danditon.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import java.util.Optional;
import java.util.stream.Stream;

import com.danditon.auth.dto.LoginRequest;
import com.danditon.auth.dto.SignupRequest;
import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;
import com.danditon.security.JwtTokenService;
import com.danditon.user.entity.User;
import com.danditon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AuthServiceTests {
	private final UserRepository users = mock(UserRepository.class);
	private final PasswordEncoder passwords = mock(PasswordEncoder.class);
	private final JwtTokenService tokens = mock(JwtTokenService.class);
	private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
	private AuthService service;

	@BeforeEach
	void setup() {
		when(passwords.encode(anyString())).thenReturn("dummy-encoded");
		when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
		service = new AuthService(users, passwords, tokens, manager);
	}

	@Test
	void missingAndWrongPasswordHaveIdenticalErrorsAndMissingAlwaysMatchesPrecomputedDummy() {
		when(users.findByEmail("missing@example.com")).thenReturn(Optional.empty());
		when(users.findByEmail("known@example.com")).thenReturn(Optional.of(User.create("known@example.com", "stored-encoded", "name")));
		var missing = assertThrows(BusinessException.class, () -> service.login(new LoginRequest("missing@example.com", "wrong-input")));
		var wrong = assertThrows(BusinessException.class, () -> service.login(new LoginRequest("known@example.com", "wrong-input")));
		assertEquals(ErrorCode.UNAUTHORIZED, missing.getErrorCode());
		assertEquals(missing.getErrorCode(), wrong.getErrorCode());
		assertEquals(ErrorCode.UNAUTHORIZED.getMessage(), missing.getMessage());
		assertEquals(missing.getMessage(), wrong.getMessage());
		verify(passwords).matches("wrong-input", "dummy-encoded");
		verify(passwords).matches("wrong-input", "stored-encoded");
		verify(passwords, times(1)).encode(anyString());
		verifyNoInteractions(tokens);
	}

	static Stream<Arguments> integrityCases() {
		return Stream.of(Arguments.of("23505", 0, true, true),
			Arguments.of("23000", 1062, true, true), Arguments.of("23505", 0, false, false),
			Arguments.of("23000", 1062, false, false), Arguments.of("23000", 1048, true, false),
			Arguments.of("23502", 0, true, false), Arguments.of(null, 1062, true, false));
	}

	@ParameterizedTest
	@MethodSource("integrityCases")
	void integrityMappingRequiresUniqueSqlCauseAndFreshMatchingRow(String state, int vendor, boolean row, boolean duplicate) {
		var failure = new DataIntegrityViolationException("sanitized fixture", new IllegalStateException(new SQLException("sanitized", state, vendor)));
		when(users.existsByEmail("user@example.com")).thenReturn(false, row);
		when(users.saveAndFlush(any())).thenThrow(failure);
		var request = new SignupRequest(" USER@EXAMPLE.COM ", "fixture-password", " name ");
		if (duplicate) {
			assertEquals(ErrorCode.DUPLICATE_EMAIL, assertThrows(BusinessException.class, () -> service.signup(request)).getErrorCode());
		} else {
			assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.signup(request)));
		}
		boolean unique = "23505".equals(state) || ("23000".equals(state) && vendor == 1062);
		var order = inOrder(users, manager);
		order.verify(manager).getTransaction(any());
		order.verify(users).existsByEmail("user@example.com");
		order.verify(users).saveAndFlush(any());
		order.verify(manager).rollback(any());
		if (unique) {
			order.verify(manager).getTransaction(argThat(def -> def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
			order.verify(users).existsByEmail("user@example.com");
			order.verify(manager).commit(any());
		}
		verify(users, times(unique ? 2 : 1)).existsByEmail("user@example.com");
		verifyNoInteractions(tokens);
	}
}
