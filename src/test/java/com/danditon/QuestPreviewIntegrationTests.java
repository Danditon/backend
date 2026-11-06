package com.danditon;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.danditon.common.llm.LlmClient;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuestPreviewIntegrationTests {

	private static final String BODY = """
		{"placeName":"○○ 떡볶이","address":"대구 중구","longingText":"매일 가던 곳","questType":"GENERAL"}
		""";

	@Autowired MockMvc mvc;
	@MockitoBean LlmClient llmClient;

	@Test
	void convertsSnakeCaseAnswerEvenWhenWrappedInCodeBlock() throws Exception {
		when(llmClient.complete(anyString(), anyString())).thenReturn("""
			```json
			{"quest_title":"떡볶이집 안부 확인하기","quest_description":"영업 중인지 확인해 주세요","difficulty":2,
			 "difficulty_reason":"영업 확인 필요","keywords":["학창시절","분식"]}
			```
			""");

		preview(BODY)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.questTitle").value("떡볶이집 안부 확인하기"))
			.andExpect(jsonPath("$.difficulty").value(2))
			.andExpect(jsonPath("$.difficultyReason").value("영업 확인 필요"))
			.andExpect(jsonPath("$.keywords[1]").value("분식"));
	}

	@Test
	void unparsableOrOutOfRangeAnswerBecomesAiError() throws Exception {
		when(llmClient.complete(anyString(), anyString())).thenReturn("죄송해요, 만들 수 없어요");
		preview(BODY).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_ERROR"));

		when(llmClient.complete(anyString(), anyString())).thenReturn(
			"{\"quest_title\":\"a\",\"quest_description\":\"b\",\"difficulty\":9,\"difficulty_reason\":\"c\",\"keywords\":[]}");
		preview(BODY).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_ERROR"));
	}

	@Test
	void missingFieldIsInvalidInput() throws Exception {
		preview("{\"placeName\":\"a\"}")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	private ResultActions preview(String body) throws Exception {
		return mvc.perform(post("/api/quests/preview").contentType(MediaType.APPLICATION_JSON)
			.content(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
			.with(jwt().jwt(token -> token.subject("1"))));
	}

}
