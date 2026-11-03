package com.danditon.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// API 명세서 에러 코드 표 기준. INTERNAL_ERROR만 명세에 없는 코드 (예상 못한 예외용)
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	INVALID_INPUT(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "입력값이 올바르지 않아요"),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요해요"),
	OUT_OF_RANGE(HttpStatus.FORBIDDEN, "OUT_OF_RANGE", "퀘스트 위치 200m 이내에서만 수행할 수 있어요"),
	SELF_QUEST(HttpStatus.FORBIDDEN, "SELF_QUEST", "내가 등록한 퀘스트는 수행할 수 없어요"),
	FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없어요"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없어요"),
	DUPLICATE_EMAIL(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", "이미 가입된 이메일이에요"),
	ALREADY_PASSED(HttpStatus.CONFLICT, "ALREADY_PASSED", "이미 성공한 퀘스트예요"),
	AI_ERROR(HttpStatus.BAD_GATEWAY, "AI_ERROR", "AI 처리에 실패했어요. 잠시 후 다시 시도해 주세요"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 오류가 발생했어요");

	private final HttpStatus status;
	private final String code;
	private final String message;

}
