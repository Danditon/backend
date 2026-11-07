package com.danditon.common.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

class GeminiLlmClientTests {

	private static final String URL =
		"https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent";
	private static final String OK = """
		{"candidates":[{"content":{"parts":[{"text":"{\\"quest_title\\":\\"제목\\"}"}]},"finishReason":"STOP"}]}
		""";

	private RestClient.Builder builder;
	private MockRestServiceServer server;

	@BeforeEach
	void setUp() {
		builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
	}

	private GeminiLlmClient client(String apiKey) {
		return new GeminiLlmClient(builder, JsonMapper.builder().build(), apiKey, "test-model");
	}

	@Test
	void sendsPromptSchemaAndKeyThenReturnsText() {
		server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("x-goog-api-key", "secret"))
			.andExpect(jsonPath("$.systemInstruction.parts[0].text").value("시스템"))
			.andExpect(jsonPath("$.contents[0].parts[0].text").value("사용자"))
			.andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
			.andExpect(jsonPath("$.generationConfig.responseJsonSchema.required[0]").value("quest_title"))
			.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		assertThat(client("secret").complete("시스템", "사용자")).isEqualTo("{\"quest_title\":\"제목\"}");
		server.verify();
	}

	@Test
	void retriesOnceOnOverloadThenSucceeds() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		server.expect(requestTo(URL)).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		assertThat(client("secret").complete("s", "u")).contains("quest_title");
		server.verify();
	}

	@Test
	void retriesOnceOnTimeoutAndRateLimit() {
		server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("timeout")));
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertAiError(() -> client("secret").complete("s", "u"));
		server.verify();
	}

	@Test
	void doesNotRetryClientErrors() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

		assertAiError(() -> client("secret").complete("s", "u"));
		server.verify();
	}

	@Test
	void emptyOrBlockedAnswerIsAiError() {
		server.expect(requestTo(URL)).andRespond(withSuccess(
			"{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}", MediaType.APPLICATION_JSON));

		assertAiError(() -> client("secret").complete("s", "u"));
	}

	@Test
	void missingApiKeyIsAiErrorWithoutCalling() {
		assertAiError(() -> client("").complete("s", "u"));
		server.verify();
	}

	private static void assertAiError(Runnable call) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
			e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_ERROR));
	}

}
