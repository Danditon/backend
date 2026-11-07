package com.danditon.quest.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;
import com.danditon.common.llm.LlmClient;
import com.danditon.quest.dto.PreviewRequest;
import com.danditon.quest.dto.PreviewResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 저장하지 않고 LLM을 1회 호출해 퀘스트 제목·설명·난이도를 만든다
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestPreviewService {

	private static final int MIN_KEYWORDS = 1;
	private static final int MAX_KEYWORDS = 5;

	private final LlmClient llmClient;
	private final ObjectMapper objectMapper;
	// 영한 님(ai 저장소 llm/quest_prompt.md)이 테스트한 프롬프트. 바꾸면 ai 쪽과 같이 맞춘다
	private final String systemPrompt = loadSystemPrompt();

	private static String loadSystemPrompt() {
		try (var in = new ClassPathResource("llm/quest_prompt.md").getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("llm/quest_prompt.md를 읽지 못했어요", e);
		}
	}

	public PreviewResponse preview(PreviewRequest request) {
		String answer = llmClient.complete(systemPrompt, userPrompt(request));
		try {
			return parse(answer);
		} catch (RuntimeException e) {
			log.error("LLM 응답을 해석하지 못함: {}", answer, e);
			throw new BusinessException(ErrorCode.AI_ERROR);
		}
	}

	private String userPrompt(PreviewRequest request) {
		// 종류별 규칙은 시스템 프롬프트에 있으므로 입력값만 같은 형식으로 보낸다
		return """
			퀘스트 종류: %s
			장소명: %s
			주소: %s
			그리운 이유: %s""".formatted(request.questType(), request.placeName(), request.address(),
			request.longingText());
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
		// Gemini는 스키마의 min/max 등을 강제하지 않으므로 직접 검증한다
		JsonNode difficultyNode = node.path("difficulty");
		int difficulty = difficultyNode.isIntegralNumber() ? difficultyNode.asInt(0) : 0;
		if (difficulty < 1 || difficulty > 3) {
			throw new IllegalArgumentException("난이도가 1~3이 아니에요: " + difficultyNode);
		}
		JsonNode keywordNodes = node.path("keywords");
		if (!keywordNodes.isArray() || keywordNodes.size() < MIN_KEYWORDS || keywordNodes.size() > MAX_KEYWORDS) {
			throw new IllegalArgumentException("keywords는 " + MIN_KEYWORDS + "~" + MAX_KEYWORDS + "개여야 해요");
		}
		List<String> keywords = keywordNodes.valueStream()
			.map(k -> {
				if (!k.isString() || k.asString().isBlank()) {
					throw new IllegalArgumentException("keywords에 빈 값이 있어요");
				}
				return k.asString();
			})
			.toList();
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
