package com.danditon.auth.controller;

import com.danditon.auth.dto.AuthResponse;
import com.danditon.auth.dto.LoginRequest;
import com.danditon.auth.dto.SignupRequest;
import com.danditon.auth.service.AuthService;
import com.danditon.common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "인증", description = "이메일과 비밀번호를 이용한 회원가입 및 로그인")
public class AuthController {

	private final AuthService auth;

	public AuthController(AuthService auth) {
		this.auth = auth;
	}

	@PostMapping("/signup")
	@SecurityRequirements
	@Operation(operationId = "signup", summary = "회원가입", description = "새 사용자를 등록하고 24시간 유효한 액세스 토큰을 발급합니다. 인증 없이 호출할 수 있으며 요청의 Bearer 토큰은 무시합니다.",
		requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = SignupRequest.class),
				examples = @ExampleObject(value = "{\"email\":\"member@example.com\",\"password\":\"<password-at-least-8-chars>\",\"nickname\":\"단디\"}"))))
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "회원가입 성공", content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = AuthResponse.class), examples = @ExampleObject(value = "{\"accessToken\":\"<access-token>\",\"userId\":1,\"nickname\":\"단디\"}"))),
		@ApiResponse(responseCode = "400", description = "입력값 또는 JSON 형식 오류", content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = "{\"code\":\"INVALID_INPUT\",\"message\":\"입력값이 올바르지 않아요\"}"))),
		@ApiResponse(responseCode = "409", description = "이미 가입된 이메일", content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = "{\"code\":\"DUPLICATE_EMAIL\",\"message\":\"이미 가입된 이메일이에요\"}"))),
		@ApiResponse(responseCode = "415", description = "지원하지 않는 요청 Content-Type", content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = "{\"code\":\"INVALID_INPUT\",\"message\":\"입력값이 올바르지 않아요\"}"))),
		@ApiResponse(responseCode = "500", description = "예상하지 못한 서버 오류", content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = "{\"code\":\"INTERNAL_ERROR\",\"message\":\"서버 오류가 발생했어요\"}")))
	})
	public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(auth.signup(request));
	}

	@PostMapping("/login")
	@SecurityRequirements
	@Operation(operationId = "login", summary = "로그인", description = "이메일과 비밀번호를 확인하고 24시간 유효한 액세스 토큰을 발급합니다. 인증 없이 호출할 수 있으며 요청의 Bearer 토큰은 무시합니다. 자격 증명이 일치하지 않으면 401을 반환합니다.",
		requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = LoginRequest.class),
				examples = @ExampleObject(value = "{\"email\":\"member@example.com\",\"password\":\"<password>\"}"))))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "로그인 성공", content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = AuthResponse.class), examples = @ExampleObject(value = "{\"accessToken\":\"<access-token>\",\"userId\":1,\"nickname\":\"단디\"}"))),
		@ApiResponse(responseCode = "400", description = "입력값 또는 JSON 형식 오류", content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = "{\"code\":\"INVALID_INPUT\",\"message\":\"입력값이 올바르지 않아요\"}"))),
		@ApiResponse(responseCode = "401", description = "자격 증명 불일치", content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = "{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요해요\"}"))),
		@ApiResponse(responseCode = "415", description = "지원하지 않는 요청 Content-Type", content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = "{\"code\":\"INVALID_INPUT\",\"message\":\"입력값이 올바르지 않아요\"}"))),
		@ApiResponse(responseCode = "500", description = "예상하지 못한 서버 오류", content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = "{\"code\":\"INTERNAL_ERROR\",\"message\":\"서버 오류가 발생했어요\"}")))
	})
	public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
		return ResponseEntity.ok(auth.login(request));
	}
}
