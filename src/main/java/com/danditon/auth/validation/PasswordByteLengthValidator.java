package com.danditon.auth.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordByteLengthValidator implements ConstraintValidator<PasswordByteLength, String> {
	private int max;

	@Override
	public void initialize(PasswordByteLength constraint) {
		max = constraint.max();
	}

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
	}
}
