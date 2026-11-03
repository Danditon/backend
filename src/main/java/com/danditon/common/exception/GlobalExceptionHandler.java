package com.danditon.common.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
		ErrorCode errorCode = e.getErrorCode();
		return ResponseEntity.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode, e.getMessage()));
	}

	// 요청 검증 실패, 필수 파트·파라미터 누락, 형식 오류, 파일 용량 초과는 모두 INVALID_INPUT
	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		HandlerMethodValidationException.class,
		ConstraintViolationException.class,
		MissingServletRequestPartException.class,
		MissingServletRequestParameterException.class,
		MethodArgumentTypeMismatchException.class,
		HttpMessageNotReadableException.class,
		MaxUploadSizeExceededException.class
	})
	public ResponseEntity<ErrorResponse> handleInvalidInput(Exception e) {
		log.debug("잘못된 입력: {}", e.getMessage());
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
			.body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
		return ResponseEntity.status(ErrorCode.NOT_FOUND.getStatus())
			.body(ErrorResponse.of(ErrorCode.NOT_FOUND));
	}

	// 예상 못한 예외. 405 등 Spring이 상태 코드를 정해둔 예외는 그 상태를 유지하고, 나머지는 500
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		if (e instanceof org.springframework.web.ErrorResponse springError) {
			HttpStatusCode status = springError.getStatusCode();
			if (status.is4xxClientError()) {
				log.debug("Spring 표준 예외: {}", e.getMessage());
				return ResponseEntity.status(status)
					.body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
			}
		}
		log.error("예상하지 못한 예외", e);
		return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
			.body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
	}

}
