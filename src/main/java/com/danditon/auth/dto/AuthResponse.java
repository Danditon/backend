package com.danditon.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AuthResponse(
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
		description = "HS256 서명 JWT 액세스 토큰. 유효 기간 24시간, Authorization: Bearer <accessToken>으로 전송. 리프레시 토큰은 지원하지 않습니다.",
		example = "<access-token>") String accessToken,
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer"}, format = "int64", example = "1") Long userId,
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "단디") String nickname
) {
}
