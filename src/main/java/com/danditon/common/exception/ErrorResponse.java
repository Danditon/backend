package com.danditon.common.exception;

import io.swagger.v3.oas.annotations.media.Schema;

public record ErrorResponse(
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "INVALID_INPUT") String code,
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "입력값이 올바르지 않아요") String message
) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.getCode(), errorCode.getMessage());
	}

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.getCode(), message);
	}

}
