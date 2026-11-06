package com.danditon.auth.dto;

import java.util.Locale;

import com.danditon.auth.validation.PasswordByteLength;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "email", maxLength = 255,
		description = "앞뒤 공백을 제거하고 소문자로 정규화하는 이메일", example = "member@example.com")
	@NotBlank @Email @Size(max = 255) String email,
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
		minLength = 8, description = "8자 이상, UTF-8 인코딩 기준 최대 72바이트. 공백 제거 등 정규화를 하지 않습니다.")
	@NotBlank @Size(min = 8) @PasswordByteLength(max = 72)
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 50,
		description = "앞뒤 공백을 제거한 닉네임", example = "단디")
	@NotBlank @Size(max = 50) String nickname
) {
	public SignupRequest {
		if (email != null) email = email.trim().toLowerCase(Locale.ROOT);
		if (nickname != null) nickname = nickname.trim();
	}

	@Override
	public String toString() {
		return "SignupRequest[email=" + email + ", nickname=" + nickname + "]";
	}
}
