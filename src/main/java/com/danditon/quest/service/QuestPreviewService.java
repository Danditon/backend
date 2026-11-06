package com.danditon.quest.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;
import com.danditon.common.llm.LlmClient;
import com.danditon.quest.dto.PreviewRequest;
import com.danditon.quest.dto.PreviewResponse;
import com.danditon.quest.entity.QuestType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 저장하지 않고 LLM을 1회 호출해 퀘스트 제목·설명·난이도를 만든다
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestPreviewService {

	private static final String SYSTEM_PROMPT = """
		너는 '그리움 퀘스트' 서비스의 퀘스트 작성 도우미야.
		떠난 사람이 그리워하는 장소 정보를 받아, 남아 있는 사람이나 여행자가 대신 다녀와 안부를 전할 수 있는 퀘스트로 정리해.
		반드시 아래 JSON 객체 하나만 출력해. 설명·코드블록 표시는 쓰지 마.
		{"quest_title": "...", "quest_description": "...", "difficulty": 1~3 정수, "difficulty_reason": "...", "keywords": ["...", "..."]}
		난이도 기준: 1 = 방문·촬영만 / 2 = 영업 확인·구매 등 행동 1개 추가 / 3 = 시간대·구도 등 조건이 까다로움.
		quest_title은 25자 이내, quest_description은 한두 문장, keywords는 3개 안팎의 짧은 단어.
		""";

	private final LlmClient llmClient;
	private final ObjectMapper objectMapper;

	public PreviewResponse preview(PreviewRequest request) {
		String answer = llmClient.complete(SYSTEM_PROMPT, userPrompt(request));
		try {
			return parse(answer);
		} catch (RuntimeException e) {
			log.error("LLM 응답을 해석하지 못함: {}", answer, e);
			throw new BusinessException(ErrorCode.AI_ERROR);
		}
	}

	private String userPrompt(PreviewRequest request) {
		String typeGuide = request.questType() == QuestType.IMAGE
			? "수행자는 등록자가 올린 기준 사진과 같은 구도로 사진을 찍어야 해."
			: "수행자는 글(안부)을 남기고 사진은 선택이야.";
		return """
			장소 이름: %s
			주소: %s
			그리움 사연: %s
			퀘스트 종류: %s (%s)
			""".formatted(request.placeName(), request.address(), request.longingText(),
			request.questType(), typeGuide);
	}

	// LLM 응답의 snake_case를 camelCase 응답으로 변환한다. 코드블록으로 감싸 보내도 JSON 부분만 쓴다
	PreviewResponse parse(String answer) {
		int start = answer.indexOf('{');
		int end = answer.lastIndexOf('}');
		if (start < 0 || end < start) {
			throw new IllegalArgumentException("JSON 객체가 없어요");
		}
		JsonNode node = objectMapper.readTree(answer.substring(start, end + 1));

		String title = text(node, "quest_title", "questTitle");
		String description = text(node, "quest_description", "questDescription");
		String reason = text(node, "difficulty_reason", "difficultyReason");
		int difficulty = node.path("difficulty").asInt(0);
		if (difficulty < 1 || difficulty > 3) {
			throw new IllegalArgumentException("난이도가 1~3이 아니에요: " + difficulty);
		}
		List<String> keywords = node.path("keywords").valueStream().map(JsonNode::asString).toList();
		return new PreviewResponse(title, description, difficulty, reason, keywords);
	}

	private String text(JsonNode node, String snake, String camel) {
		JsonNode value = node.has(snake) ? node.get(snake) : node.get(camel);
		if (value == null || !value.isString() || value.asString().isBlank()) {
			throw new IllegalArgumentException(snake + " 값이 없어요");
		}
		return value.asString();
	}

}
