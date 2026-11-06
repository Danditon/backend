package com.danditon.common.llm;

// LLM 호출부. 제공자가 확정되면 구현체만 교체한다. 실패는 모두 502 AI_ERROR
public interface LlmClient {

	// 시스템 프롬프트 + 사용자 프롬프트 → 모델이 돌려준 텍스트
	String complete(String systemPrompt, String userPrompt);

}
