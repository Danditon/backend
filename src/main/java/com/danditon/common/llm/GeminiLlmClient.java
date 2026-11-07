package com.danditon.common.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Gemini(generateContent) 호출. 퀘스트 미리보기 전용이라 응답 스키마(llm/quest_schema.json)를 항상 함께 보낸다.
// 설정은 환경 변수로 주입한다 (application.yaml 수정 불필요)
//  - GEMINI_API_KEY  API 키 (없으면 호출 시 502 AI_ERROR)
//  - LLM_MODEL       모델 이름 (기본 gemini-3.5-flash-lite)
// 타임아웃 10초, 타임아웃·503·429일 때만 1회 재시도. 그 외 실패는 모두 502 AI_ERROR.
@Slf4j
@Component
public class GeminiLlmClient implements LlmClient {

	static final String BASE_URL = "https://generativelanguage.googleapis.com";
	private static final int MAX_ATTEMPTS = 2;
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final String apiKey;
	private final String model;
	private final JsonNode responseSchema;

	@Autowired
	public GeminiLlmClient(ObjectMapper objectMapper,
		@Value("${GEMINI_API_KEY:}") String apiKey,
		@Value("${LLM_MODEL:gemini-3.5-flash-lite}") String model) {
		this(RestClient.builder().requestFactory(requestFactory()), objectMapper, apiKey, model);
	}

	// 테스트에서 MockRestServiceServer를 붙일 수 있도록 빌더를 받는다
	GeminiLlmClient(RestClient.Builder builder, ObjectMapper objectMapper, String apiKey, String model) {
		this.restClient = builder.baseUrl(BASE_URL).build();
		this.objectMapper = objectMapper;
		this.apiKey = apiKey;
		this.model = model;
		this.responseSchema = loadSchema(objectMapper);
	}

	private static JdkClientHttpRequestFactory requestFactory() {
		var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
		factory.setReadTimeout(TIMEOUT);
		return factory;
	}

	private static JsonNode loadSchema(ObjectMapper objectMapper) {
		try (var in = new ClassPathResource("llm/quest_schema.json").getInputStream()) {
			return objectMapper.readTree(in);
		} catch (IOException e) {
			throw new UncheckedIOException("llm/quest_schema.json을 읽지 못했어요", e);
		}
	}

	@Override
	public String complete(String systemPrompt, String userPrompt) {
		if (apiKey == null || apiKey.isBlank()) {
			log.error("GEMINI_API_KEY가 설정되지 않아 미리보기를 만들 수 없음");
			throw new BusinessException(ErrorCode.AI_ERROR);
		}
		Map<String, Object> body = Map.of(
			"systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
			"contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))),
			"generationConfig", Map.of(
				"responseMimeType", "application/json",
				"responseJsonSchema", responseSchema));

		RuntimeException last = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return extractText(request(body));
			} catch (RuntimeException e) {
				last = e;
				boolean retryable = isRetryable(e);
				log.warn("Gemini 호출 실패 ({}/{}, 재시도 {}): {}", attempt, MAX_ATTEMPTS,
					retryable ? "함" : "안 함", e.getMessage());
				if (!retryable) {
					break;
				}
			}
		}
		log.error("Gemini 호출 최종 실패", last);
		throw new BusinessException(ErrorCode.AI_ERROR);
	}

	private JsonNode request(Map<String, Object> body) {
		return restClient.post()
			.uri("/v1beta/models/{model}:generateContent", model)
			.header("x-goog-api-key", apiKey)
			.contentType(MediaType.APPLICATION_JSON)
			.body(body)
			.retrieve()
			.body(JsonNode.class);
	}

	// 타임아웃(연결·읽기)·503·429만 다시 시도한다. 400·401·403 같은 설정·요청 오류는 재시도해도 소용없다
	private static boolean isRetryable(RuntimeException e) {
		if (e instanceof ResourceAccessException) {
			return true;
		}
		if (e instanceof HttpStatusCodeException http) {
			HttpStatusCode status = http.getStatusCode();
			return status.value() == 503 || status.value() == 429;
		}
		return false;
	}

	private String extractText(JsonNode response) {
		if (response == null) {
			throw new IllegalStateException("Gemini 응답 본문이 비어 있어요");
		}
		JsonNode candidate = response.path("candidates").path(0);
		JsonNode text = candidate.path("content").path("parts").path(0).path("text");
		if (!text.isString() || text.asString().isBlank()) {
			// 안전 필터 차단·토큰 초과 등이면 text가 없다
			throw new IllegalStateException("Gemini 응답에 텍스트가 없어요 (finishReason="
				+ candidate.path("finishReason").asString("없음") + ")");
		}
		return text.asString();
	}

}
