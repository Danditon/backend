package com.danditon.auth.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
	ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordByteLengthValidator.class)
public @interface PasswordByteLength {
	String message() default "must be at most {max} UTF-8 bytes";
	Class<?>[] groups() default {};
	Class<? extends Payload>[] payload() default {};
	int max() default 72;
}
