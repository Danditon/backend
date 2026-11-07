package com.danditon.auth.dto;

import java.util.Locale;

import com.danditon.auth.validation.PasswordByteLength;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
	@NotBlank @Email @Size(max = 255) String email,
	@NotBlank @Size(min = 8) @PasswordByteLength(max = 72)
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
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
