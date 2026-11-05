package com.danditon.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.danditon.auth.dto.AuthResponse;
import com.danditon.auth.dto.LoginRequest;
import com.danditon.auth.dto.SignupRequest;
import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;
import com.danditon.security.JwtTokenService;
import com.danditon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:auth-service-integration;DB_CLOSE_DELAY=0;LOCK_TIMEOUT=10000",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"logging.level.org.hibernate.orm.jdbc.error=OFF"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(45)
class AuthServiceIntegrationTests {
	@Autowired private AuthService service;
	@Autowired private UserRepository users;
	@Autowired private PasswordEncoder passwords;
	@Autowired private JwtTokenService tokens;
	@Autowired private JwtDecoder decoder;
	@Autowired private PlatformTransactionManager manager;
	@Autowired private JdbcTemplate jdbc;

	@BeforeEach
	void clearUsers() {
		users.deleteAllInBatch();
	}

	@Test
	void canonicalSignupAndLoginPersistExactPasswordAndIssueAcceptedTokens() {
		String password = "  fixture-password  ";
		var signup = service.signup(new SignupRequest(" USER@EXAMPLE.COM ", password, " name "));
		var user = users.findById(signup.userId()).orElseThrow();
		assertEquals("user@example.com", user.getEmail());
		assertEquals("name", user.getNickname());
		assertTrue(user.getPasswordHash().startsWith("{bcrypt}"));
		assertTrue(passwords.matches(password, user.getPasswordHash()));
		assertFalse(passwords.matches(password.trim(), user.getPasswordHash()));
		var login = service.login(new LoginRequest(" USER@EXAMPLE.COM ", password));
		for (var response : List.of(signup, login)) {
			assertEquals(user.getId(), response.userId());
			assertEquals("name", response.nickname());
			assertEquals(user.getId().toString(), decoder.decode(response.accessToken()).getSubject());
		}
		var wrong = assertThrows(BusinessException.class, () -> service.login(new LoginRequest("user@example.com", "wrong-input")));
		var missing = assertThrows(BusinessException.class, () -> service.login(new LoginRequest("missing@example.com", "wrong-input")));
		assertEquals(ErrorCode.UNAUTHORIZED, wrong.getErrorCode());
		assertEquals(wrong.getErrorCode(), missing.getErrorCode());
		assertEquals(ErrorCode.UNAUTHORIZED.getMessage(), wrong.getMessage());
		assertEquals(wrong.getMessage(), missing.getMessage());
		assertEquals(ErrorCode.DUPLICATE_EMAIL, assertThrows(BusinessException.class,
			() -> service.signup(new SignupRequest(" USER@EXAMPLE.COM ", password, "other"))).getErrorCode());
		assertEquals(1, users.count());
		var summary = jdbc.queryForMap("select count(*) as row_count, min(email) as email, min(nickname) as nickname, min(case when password_hash like '{bcrypt}%' then 1 else 0 end) as bcrypt_prefix from users");
		assertEquals(1L, ((Number) summary.get("row_count")).longValue());
		assertEquals(1, ((Number) summary.get("bcrypt_prefix")).intValue());
		System.out.println("SANITIZED_H2 " + summary);
	}

	@Test
	void callerRollbackRemovesSignupAccount() {
		var outer = new TransactionTemplate(manager);
		var response = outer.execute(status -> {
			var signup = service.signup(new SignupRequest("outer-rollback@example.com", "fixture-password", "name"));
			assertEquals(1, users.count());
			assertEquals(signup.userId().toString(), decoder.decode(signup.accessToken()).getSubject());
			status.setRollbackOnly();
			return signup;
		});
		assertNotNull(response);
		assertEquals(0, users.count());
		assertFalse(users.existsById(response.userId()));
		assertFalse(users.existsByEmail("outer-rollback@example.com"));
		assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(BusinessException.class,
			() -> service.login(new LoginRequest("outer-rollback@example.com", "fixture-password"))).getErrorCode());
		System.out.println("OUTER_ROLLBACK flushed_rows=1 committed_rows=0 account_absent=true login=UNAUTHORIZED");
	}

	@Test
	void tokenIssuanceFailureRollsBackFlushedUser() {
		var issuer = mock(JwtTokenService.class);
		var failure = new IllegalStateException("issuer unavailable");
		when(issuer.issueAccessToken(anyLong())).thenAnswer(call -> {
			assertEquals(1, users.count());
			throw failure;
		});
		var failing = new AuthService(users, passwords, issuer, manager);
		assertSame(failure, assertThrows(IllegalStateException.class,
			() -> failing.signup(new SignupRequest("rollback@example.com", "fixture-password", "name"))));
		assertEquals(0, users.count());
		assertFalse(users.existsByEmail("rollback@example.com"));
		System.out.println("ROLLBACK flushed_rows=1 committed_rows=0");
	}

	@Test
	void simultaneousPrechecksProduceOneSuccessOneDuplicateAndOneCommittedRow() throws Exception {
		var barrier = new CyclicBarrier(2);
		var prechecks = new AtomicInteger();
		var repository = mock(UserRepository.class, delegatesTo(users));
		doAnswer(call -> {
			boolean exists = users.existsByEmail("race@example.com");
			if (prechecks.incrementAndGet() <= 2) {
				assertFalse(exists);
				barrier.await(10, TimeUnit.SECONDS);
			}
			return exists;
		}).when(repository).existsByEmail("race@example.com");
		var racing = new AuthService(repository, passwords, tokens, manager);
		var executor = Executors.newFixedThreadPool(2);
		try {
			java.util.concurrent.Callable<Object> signup = () -> {
				try {
					return racing.signup(new SignupRequest(" RACE@EXAMPLE.COM ", "fixture-password", "name"));
				} catch (BusinessException exception) {
					return exception.getErrorCode();
				}
			};
			var first = executor.submit(signup);
			var second = executor.submit(signup);
			var results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
			assertEquals(1, results.stream().filter(AuthResponse.class::isInstance).count());
			assertEquals(1, results.stream().filter(ErrorCode.DUPLICATE_EMAIL::equals).count());
			assertEquals(3, prechecks.get());
			assertEquals(1, users.count());
			var winner = results.stream().filter(AuthResponse.class::isInstance).map(AuthResponse.class::cast).findFirst().orElseThrow();
			assertEquals(users.findByEmail("race@example.com").orElseThrow().getId(), winner.userId());
			assertEquals(winner.userId().toString(), decoder.decode(winner.accessToken()).getSubject());
			System.out.println("RACE initial_false_prechecks=2 success=1 duplicate=1 committed_rows=1 fresh_lookup=1 decoder=accepted");
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
			System.out.println("RACE executor_terminated=true");
		}
	}
}
