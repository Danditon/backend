package com.danditon.auth.dto;

import java.util.Locale;

import com.danditon.auth.validation.PasswordByteLength;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
	@NotBlank @Email @Size(max = 255) String email,
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
