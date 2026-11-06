package com.danditon.security;

import org.springframework.security.oauth2.jwt.Jwt;

import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;

// JWT의 sub(= userId)에서 로그인 사용자 id를 꺼낸다
public final class CurrentUser {

	private CurrentUser() {
	}

	public static Long id(Jwt jwt) {
		try {
			return Long.valueOf(jwt.getSubject());
		} catch (NumberFormatException | NullPointerException e) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
	}

}
