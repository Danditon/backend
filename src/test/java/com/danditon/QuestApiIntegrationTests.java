package com.danditon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

import com.danditon.common.embedding.EmbeddingClient;
import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;
import com.danditon.quest.repository.QuestRepository;
import com.danditon.submission.repository.SubmissionRepository;
import com.danditon.user.entity.User;
import com.danditon.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuestApiIntegrationTests {

	private static final byte[] JPG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3};
	// 대구 중구 기준점과, 약 100m / 약 1km 떨어진 점
	private static final double LAT = 35.8714;
	private static final double LNG = 128.6014;
	private static final double NEAR_LAT = 35.8723;
	private static final double FAR_LAT = 35.8804;

	@TempDir
	static Path storage;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("danditon.storage.root", () -> storage.toString());
		registry.add("danditon.storage.base-url", () -> "http://test.local");
		registry.add("embedding.similarity-threshold", () -> "0.8");
	}

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired QuestRepository quests;
	@Autowired SubmissionRepository submissions;
	@MockitoBean EmbeddingClient embeddingClient;

	Long author;
	Long performer;

	@BeforeEach
	void setUp() throws Exception {
		for (String directory : List.of("quest", "submission")) {
			for (Path file : filesUnder(storage.resolve(directory))) {
				Files.delete(file);
			}
		}
		submissions.deleteAll();
		quests.deleteAll();
		users.deleteAll();
		author = users.save(User.create("author@test.com", "hash", "떠난사람")).getId();
		performer = users.save(User.create("performer@test.com", "hash", "남은사람")).getId();
	}

	@Test
	void generalQuestFlow_createListDetailSubmit() throws Exception {
		Long questId = createQuest("GENERAL", null);

		mvc.perform(get("/api/quests").param("lat", "" + LAT).param("lng", "" + LNG).with(as(performer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].questId").value(questId))
			.andExpect(jsonPath("$[0].distance").value(0))
			.andExpect(jsonPath("$[0].isMine").value(false));
		mvc.perform(get("/api/quests").param("lat", "" + LAT).param("lng", "" + LNG).with(as(author)))
			.andExpect(jsonPath("$[0].isMine").value(true));

		mvc.perform(get("/api/quests/" + questId).with(as(performer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.photoUrl").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.author.nickname").value("떠난사람"))
			.andExpect(jsonPath("$.myStatus").value("NONE"))
			.andExpect(jsonPath("$.isMine").value(false));

		submit(questId, performer, NEAR_LAT, null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.verifyStatus").value("PASSED"))
			.andExpect(jsonPath("$.similarity").value(org.hamcrest.Matchers.nullValue()));

		mvc.perform(get("/api/quests/" + questId).with(as(performer)))
			.andExpect(jsonPath("$.myStatus").value("PASSED"));
		submit(questId, performer, NEAR_LAT, null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_PASSED"));
	}

	@Test
	void imageQuestFlow_embeddingRoundTripAndVerification() throws Exception {
		List<Float> reference = vector(1f, 0f);
		when(embeddingClient.embed(any())).thenReturn(reference);
		Long questId = createQuest("IMAGE", new MockMultipartFile("photo", "ref.jpg", "image/jpeg", JPG));

		// JSON 컬럼 왕복: 저장된 임베딩이 512차원 그대로 읽힌다
		assertThat(quests.findById(questId).orElseThrow().getPhotoEmbedding()).hasSize(512).isEqualTo(reference);
		mvc.perform(get("/api/quests/" + questId).with(as(performer)))
			.andExpect(jsonPath("$.photoUrl").value(org.hamcrest.Matchers.startsWith("http://test.local/uploads/quest/")));

		// 유사도 미달 → 201 + FAILED, 재시도 허용
		when(embeddingClient.embed(any())).thenReturn(vector(0f, 1f));
		submit(questId, performer, NEAR_LAT, photo())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.verifyStatus").value("FAILED"));
		mvc.perform(get("/api/quests/" + questId).with(as(performer)))
			.andExpect(jsonPath("$.myStatus").value("FAILED"));

		when(embeddingClient.embed(any())).thenReturn(reference);
		submit(questId, performer, NEAR_LAT, photo())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.verifyStatus").value("PASSED"))
			.andExpect(jsonPath("$.similarity").value(1.0));
	}

	@Test
	void imageQuestCreate_embeddingFailureRollsBackAndDeletesPhoto() throws Exception {
		when(embeddingClient.embed(any())).thenThrow(new BusinessException(ErrorCode.AI_ERROR));

		createQuestRaw("IMAGE", new MockMultipartFile("photo", "ref.jpg", "image/jpeg", JPG), author)
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("AI_ERROR"));

		assertThat(quests.count()).isZero();
		assertThat(filesUnder(storage.resolve("quest"))).isEmpty();
	}

	@Test
	void submit_embeddingFailureStoresNothing() throws Exception {
		when(embeddingClient.embed(any())).thenReturn(vector(1f, 0f));
		Long questId = createQuest("IMAGE", new MockMultipartFile("photo", "ref.jpg", "image/jpeg", JPG));
		when(embeddingClient.embed(any())).thenThrow(new BusinessException(ErrorCode.AI_ERROR));

		submit(questId, performer, NEAR_LAT, photo())
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("AI_ERROR"));

		assertThat(submissions.count()).isZero();
		assertThat(filesUnder(storage.resolve("submission"))).isEmpty();
	}

	@Test
	void submit_validationOrder() throws Exception {
		Long questId = createQuest("GENERAL", null);

		submit(questId, author, NEAR_LAT, null)
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SELF_QUEST"));
		submit(questId, performer, FAR_LAT, null)
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_RANGE"));
		assertThat(submissions.count()).isZero();
		submit(999L, performer, NEAR_LAT, null)
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

		when(embeddingClient.embed(any())).thenReturn(vector(1f, 0f));
		Long imageQuestId = createQuest("IMAGE", new MockMultipartFile("photo", "ref.jpg", "image/jpeg", JPG));
		submit(imageQuestId, performer, NEAR_LAT, null)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	void createQuest_requiredFieldsPerType() throws Exception {
		createQuestRaw("IMAGE", null, author)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mvc.perform(multipart("/api/quests")
				.file(new MockMultipartFile("request", "", "application/json", "{\"questType\":\"GENERAL\"}".getBytes(StandardCharsets.UTF_8)))
				.with(as(author)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	void list_filtersByRadiusTypeAndDifficulty_sortedByDistance() throws Exception {
		createQuest("GENERAL", null);
		mvc.perform(get("/api/quests").param("lat", "" + FAR_LAT).param("lng", "" + LNG)
				.param("radius", "500").with(as(performer)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/quests").param("lat", "" + LAT).param("lng", "" + LNG)
				.param("type", "IMAGE").with(as(performer)))
			.andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/quests").param("lat", "" + LAT).param("lng", "" + LNG)
				.param("difficulty", "2").with(as(performer)))
			.andExpect(jsonPath("$.length()").value(1));
		mvc.perform(get("/api/quests").with(as(performer)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void submissionList_visibilityByViewerRole() throws Exception {
		Long questId = createQuest("GENERAL", null);
		Long other = users.save(User.create("other@test.com", "hash", "구경꾼")).getId();
		submit(questId, performer, NEAR_LAT, null).andExpect(status().isCreated());

		mvc.perform(get("/api/quests/" + questId + "/submissions").with(as(author)))
			.andExpect(jsonPath("$.viewerRole").value("AUTHOR"))
			.andExpect(jsonPath("$.submissions.length()").value(1))
			.andExpect(jsonPath("$.submissions[0].performer.nickname").value("남은사람"))
			.andExpect(jsonPath("$.submissions[0].isLiked").value(false));
		mvc.perform(get("/api/quests/" + questId + "/submissions").with(as(performer)))
			.andExpect(jsonPath("$.viewerRole").value("COMPLETED"))
			.andExpect(jsonPath("$.submissions.length()").value(1));
		mvc.perform(get("/api/quests/" + questId + "/submissions").with(as(other)))
			.andExpect(jsonPath("$.viewerRole").value("PERFORMER"))
			.andExpect(jsonPath("$.submissions.length()").value(0));
	}

	@Test
	void requiresAuthentication() throws Exception {
		mvc.perform(get("/api/quests").param("lat", "35").param("lng", "128"))
			.andExpect(status().isUnauthorized());
	}

	// ---- helpers ----

	private RequestPostProcessor as(Long userId) {
		return SecurityMockMvcRequestPostProcessors.jwt().jwt(token -> token.subject(String.valueOf(userId)));
	}

	private MockMultipartFile photo() {
		return new MockMultipartFile("photo", "p.jpg", "image/jpeg", JPG);
	}

	private Long createQuest(String type, MockMultipartFile photo) throws Exception {
		String body = createQuestRaw(type, photo, author).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		return Long.valueOf(body.replaceAll("\\D", ""));
	}

	private ResultActions createQuestRaw(String type, MockMultipartFile photo, Long userId) throws Exception {
		String json = """
			{"questType":"%s","placeName":"○○ 떡볶이","address":"대구 중구","lat":%s,"lng":%s,
			 "longingText":"매일 가던 곳","questTitle":"안부 확인하기","questDescription":"사진을 남겨주세요","difficulty":2}
			""".formatted(type, LAT, LNG);
		var builder = multipart("/api/quests")
			.file(new MockMultipartFile("request", "", "application/json", json.getBytes(StandardCharsets.UTF_8)));
		if (photo != null) {
			builder.file(photo);
		}
		return mvc.perform(builder.with(as(userId)));
	}

	private ResultActions submit(Long questId, Long userId, double lat, MockMultipartFile photo) throws Exception {
		String json = """
			{"greeting":"아직 그대로예요","lat":%s,"lng":%s}
			""".formatted(lat, LNG);
		var builder = multipart("/api/quests/" + questId + "/submissions")
			.file(new MockMultipartFile("request", "", "application/json", json.getBytes(StandardCharsets.UTF_8)));
		if (photo != null) {
			builder.file(photo);
		}
		return mvc.perform(builder.with(as(userId)));
	}

	// 512차원 중 앞 두 칸만 쓰는 단위 벡터
	private static List<Float> vector(float first, float second) {
		List<Float> v = new ArrayList<>(512);
		v.add(first);
		v.add(second);
		for (int i = 2; i < 512; i++) {
			v.add(0f);
		}
		return v;
	}

	private static List<Path> filesUnder(Path directory) throws Exception {
		if (!Files.exists(directory)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.list(directory)) {
			return walk.toList();
		}
	}

}
