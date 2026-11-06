package com.danditon.common.llm;

import org.springframework.stereotype.Component;

import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

// 임시 구현: LLM 제공자·키가 확정되기 전까지 호출하면 502 AI_ERROR.
// 제공자 확정 시 이 클래스를 실제 구현(RestClient)으로 교체한다. 키는 서버 환경 변수로만 주입한다.
@Slf4j
@Component
public class NotConfiguredLlmClient implements LlmClient {

	@Override
	public String complete(String systemPrompt, String userPrompt) {
		log.error("LLM 제공자가 아직 설정되지 않아 미리보기를 만들 수 없음");
		throw new BusinessException(ErrorCode.AI_ERROR);
	}

}
