package com.danditon.common.embedding;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

// 이미지 임베딩 호출부. 자체 임베딩 서버(HttpEmbeddingClient)가 어려우면 외부 API 구현으로 교체한다.
public interface EmbeddingClient {

	// 사진 1장을 512차원 L2 정규화 벡터로 변환한다. 서버 오류·타임아웃·형식 불일치는 모두 502 AI_ERROR
	List<Float> embed(MultipartFile photo);

	// L2 정규화된 벡터끼리의 내적 = 코사인 유사도
	static double similarity(List<Float> a, List<Float> b) {
		if (a == null || b == null || a.size() != b.size()) {
			throw new IllegalArgumentException("임베딩 차원이 맞지 않아요");
		}
		double sum = 0;
		for (int i = 0; i < a.size(); i++) {
			sum += (double) a.get(i) * b.get(i);
		}
		return sum;
	}

}
