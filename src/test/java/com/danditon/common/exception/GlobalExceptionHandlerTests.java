package com.danditon.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTests {

	private static final String PASSWORD_SENTINEL = "task4-password-7b82d31c-DoNotLog!";
	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void validationLogsOnlyExceptionTypeWithoutRejectedPassword() throws Exception {
		var binding = new BeanPropertyBindingResult(new Object(), "signup");
		binding.addError(new FieldError("signup", "password", PASSWORD_SENTINEL, false,
			null, null, "invalid password " + PASSWORD_SENTINEL));
		var parameter = new MethodParameter(
			GlobalExceptionHandlerTests.class.getDeclaredMethod("request", Object.class), 0);
		var exception = new MethodArgumentNotValidException(parameter, binding);
		assertTrue(exception.getMessage().contains(PASSWORD_SENTINEL));

		captureLogs(events -> {
			assertResponse(handler.handleInvalidInput(exception), 400, "INVALID_INPUT", "입력값이 올바르지 않아요");
			assertSafeMetadata(events, exception, "잘못된 입력: ");
		});
	}

	@Test
	void malformedJsonLogsOnlyExceptionTypeWithoutPasswordOrCause() {
		var input = new MockHttpInputMessage(("{\"password\":\"" + PASSWORD_SENTINEL)
			.getBytes(StandardCharsets.UTF_8));
		var exception = new HttpMessageNotReadableException("Malformed JSON " + PASSWORD_SENTINEL,
			new IllegalArgumentException(PASSWORD_SENTINEL), input);
		assertTrue(exception.getMessage().contains(PASSWORD_SENTINEL));

		captureLogs(events -> {
			assertResponse(handler.handleInvalidInput(exception), 400, "INVALID_INPUT", "입력값이 올바르지 않아요");
			assertSafeMetadata(events, exception, "잘못된 입력: ");
		});
	}

	@Test
	void springClientErrorPreservesStatusAndLogsOnlyExceptionType() {
		var exception = new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, PASSWORD_SENTINEL,
			new IllegalArgumentException(PASSWORD_SENTINEL));
		assertTrue(exception.getMessage().contains(PASSWORD_SENTINEL));

		captureLogs(events -> {
			assertResponse(handler.handleUnexpected(exception), 405, "INVALID_INPUT", "입력값이 올바르지 않아요");
			assertSafeMetadata(events, exception, "Spring 표준 예외: ");
		});
	}

	@ParameterizedTest
	@CsvSource({
		"INVALID_INPUT,400,입력값이 올바르지 않아요",
		"UNAUTHORIZED,401,로그인이 필요해요",
		"DUPLICATE_EMAIL,409,이미 가입된 이메일이에요"
	})
	void businessErrorsPreserveExactStatusAndBodyWithoutLogging(ErrorCode code, int status, String message) {
		captureLogs(events -> {
			assertResponse(handler.handleBusiness(new BusinessException(code)), status, code.name(), message);
			assertTrue(events.isEmpty());
		});
	}

	@Test
	void businessErrorPreservesCustomMessageWithoutLogging() {
		captureLogs(events -> {
			assertResponse(handler.handleBusiness(new BusinessException(ErrorCode.UNAUTHORIZED, "custom message")),
				401, "UNAUTHORIZED", "custom message");
			assertTrue(events.isEmpty());
		});
	}

	@Test
	void unexpectedErrorKeepsExistingServerErrorBoundary() {
		var exception = new IllegalStateException("unexpected fixture failure");
		captureLogs(events -> {
			assertResponse(handler.handleUnexpected(exception), 500, "INTERNAL_ERROR", "서버 오류가 발생했어요");
			assertEquals(1, events.size());
			assertEquals(Level.ERROR, events.get(0).getLevel());
			assertEquals("예상하지 못한 예외", events.get(0).getFormattedMessage());
			assertEquals(exception.getClass().getName(), events.get(0).getThrowableProxy().getClassName());
		});
	}

	private static void assertSafeMetadata(List<ILoggingEvent> events, Exception exception, String prefix) {
		assertEquals(1, events.size());
		var event = events.get(0);
		assertFalse(event.getFormattedMessage().contains(PASSWORD_SENTINEL));
		assertNull(event.getThrowableProxy());
		assertEquals(Level.DEBUG, event.getLevel());
		assertEquals(prefix + exception.getClass().getSimpleName(), event.getFormattedMessage());
		assertEquals(1, event.getArgumentArray().length);
		assertEquals(exception.getClass().getSimpleName(), event.getArgumentArray()[0]);
		System.out.println("SAFE_CAPTURE " + event.getLevel() + " " + event.getFormattedMessage()
			+ " throwable=absent sentinelMatches=0");
	}

	private static void assertResponse(ResponseEntity<ErrorResponse> response, int status, String code, String message) {
		assertEquals(status, response.getStatusCode().value());
		assertEquals(new ErrorResponse(code, message), response.getBody());
		System.out.println("RESPONSE status=" + status + " code=" + code + " message=" + message);
	}

	private static void captureLogs(Consumer<List<ILoggingEvent>> assertions) {
		var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		Level previousLevel = logger.getLevel();
		boolean previousAdditive = logger.isAdditive();
		var appender = new ListAppender<ILoggingEvent>();
		appender.start();
		try {
			logger.setLevel(Level.DEBUG);
			logger.setAdditive(false);
			logger.addAppender(appender);
			assertions.accept(appender.list);
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(previousLevel);
			logger.setAdditive(previousAdditive);
			appender.stop();
			assertFalse(logger.isAttached(appender));
			assertSame(previousLevel, logger.getLevel());
			assertEquals(previousAdditive, logger.isAdditive());
			System.out.println("LOGGER_CLEANUP appender=detached level=restored additive=restored");
		}
	}

	private static void request(Object body) {
	}
}
