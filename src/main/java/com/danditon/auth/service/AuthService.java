package com.danditon.auth.service;

import java.sql.SQLException;
import java.util.UUID;

import com.danditon.auth.dto.AuthResponse;
import com.danditon.auth.dto.LoginRequest;
import com.danditon.auth.dto.SignupRequest;
import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;
import com.danditon.security.JwtTokenService;
import com.danditon.user.entity.User;
import com.danditon.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {

	private final UserRepository users;
	private final PasswordEncoder passwords;
	private final JwtTokenService tokens;
	private final TransactionTemplate transaction;
	private final TransactionTemplate recoveryTransaction;
	private final String dummyHash;

	public AuthService(UserRepository users, PasswordEncoder passwords, JwtTokenService tokens,
			PlatformTransactionManager transactionManager) {
		this.users = users;
		this.passwords = passwords;
		this.tokens = tokens;
		this.transaction = new TransactionTemplate(transactionManager);
		this.recoveryTransaction = new TransactionTemplate(transactionManager);
		this.recoveryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.dummyHash = passwords.encode(UUID.randomUUID().toString());
	}

	public AuthResponse signup(SignupRequest request) {
		try {
			return transaction.execute(status -> {
				if (users.existsByEmail(request.email())) {
					throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
				}
				User user = users.saveAndFlush(User.create(request.email(),
					passwords.encode(request.password()), request.nickname()));
				return response(user);
			});
		} catch (DataIntegrityViolationException exception) {
			if (isUniqueViolation(exception)
					&& Boolean.TRUE.equals(recoveryTransaction.execute(status -> users.existsByEmail(request.email())))) {
				throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
			}
			throw exception;
		}
	}

	public AuthResponse login(LoginRequest request) {
		var user = users.findByEmail(request.email());
		boolean matches = passwords.matches(request.password(), user.map(User::getPasswordHash).orElse(dummyHash));
		if (user.isEmpty() || !matches) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return response(user.get());
	}

	private AuthResponse response(User user) {
		return new AuthResponse(tokens.issueAccessToken(user.getId()), user.getId(), user.getNickname());
	}

	private static boolean isUniqueViolation(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql && ("23505".equals(sql.getSQLState())
					|| ("23000".equals(sql.getSQLState()) && sql.getErrorCode() == 1062))) {
				return true;
			}
		}
		return false;
	}
}
