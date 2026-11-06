package com.danditon.auth.dto;

import java.util.Locale;

import com.danditon.auth.validation.PasswordByteLength;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "email", maxLength = 255,
		description = "앞뒤 공백을 제거하고 소문자로 정규화하는 이메일", example = "member@example.com")
	@NotBlank @Email @Size(max = 255) String email,
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
		description = "가입한 비밀번호. UTF-8 인코딩 기준 최대 72바이트이며 정규화하지 않습니다. 가입 시 최소 길이 정책을 로그인에 적용하지 않습니다.")
	@NotBlank @PasswordByteLength(max = 72)
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password
) {
	public LoginRequest {
		if (email != null) email = email.trim().toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		return "LoginRequest[email=" + email + "]";
	}
}
