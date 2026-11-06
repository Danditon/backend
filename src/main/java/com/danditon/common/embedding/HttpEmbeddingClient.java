package com.danditon.common.embedding;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

// 자체 호스팅 CLIP 임베딩 서버 호출. 설정은 환경 변수로 덮어쓴다 (application.yaml 수정 불필요)
//  - EMBED_HOST  임베딩 서버 주소 (기본 http://localhost:8000)
// 타임아웃 5초, 실패 시 1회 재시도. 400·500·503 MODEL_LOADING 모두 502 AI_ERROR로 변환한다.
@Slf4j
@Component
public class HttpEmbeddingClient implements EmbeddingClient {

	static final int DIMENSION = 512;
	private static final int MAX_ATTEMPTS = 2;
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final RestClient restClient;

	public HttpEmbeddingClient(@Value("${embed.host:http://localhost:8000}") String host) {
		var requestFactory = new JdkClientHttpRequestFactory(
			HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
		requestFactory.setReadTimeout(TIMEOUT);
		this.restClient = RestClient.builder().baseUrl(host).requestFactory(requestFactory).build();
	}

	@Override
	public List<Float> embed(MultipartFile photo) {
		byte[] bytes;
		try {
			bytes = photo.getBytes();
		} catch (IOException e) {
			log.error("임베딩 요청용 사진을 읽지 못함", e);
			throw new BusinessException(ErrorCode.AI_ERROR);
		}
		String fileName = photo.getOriginalFilename() == null ? "photo" : photo.getOriginalFilename();

		RuntimeException last = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return requestEmbedding(bytes, fileName, photo.getContentType());
			} catch (RuntimeException e) {
				last = e;
				log.warn("임베딩 서버 호출 실패 ({}/{}): {}", attempt, MAX_ATTEMPTS, e.getMessage());
			}
		}
		log.error("임베딩 서버 호출 최종 실패", last);
		throw new BusinessException(ErrorCode.AI_ERROR);
	}

	private List<Float> requestEmbedding(byte[] bytes, String fileName, String contentType) {
		var image = new ByteArrayResource(bytes) {
			@Override
			public String getFilename() {
				return fileName;
			}
		};
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		var headers = new org.springframework.http.HttpHeaders();
		if (contentType != null) {
			headers.setContentType(MediaType.parseMediaType(contentType));
		}
		body.add("image", new org.springframework.http.HttpEntity<>(image, headers));

		EmbedResponse response = restClient.post()
			.uri("/embed")
			.contentType(MediaType.MULTIPART_FORM_DATA)
			.body(body)
			.retrieve()
			.body(EmbedResponse.class);

		if (response == null || response.embedding() == null
			|| response.dim() != DIMENSION || response.embedding().size() != DIMENSION) {
			throw new IllegalStateException("임베딩 응답 형식이 올바르지 않아요");
		}
		return response.embedding();
	}

	record EmbedResponse(String model, int dim, List<Float> embedding) {
	}

}
