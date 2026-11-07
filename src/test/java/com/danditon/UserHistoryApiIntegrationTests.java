package com.danditon;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;

import com.danditon.quest.entity.Quest;
import com.danditon.quest.entity.QuestType;
import com.danditon.quest.repository.QuestRepository;
import com.danditon.submission.entity.Submission;
import com.danditon.submission.entity.VerifyStatus;
import com.danditon.submission.repository.SubmissionRepository;
import com.danditon.user.entity.User;
import com.danditon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:history-http-${random.uuid};DB_CLOSE_DELAY=0",
	"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
	"danditon.storage.base-url=https://photos.example.test"
})
@ActiveProfiles("test")
@DirtiesContext
@Timeout(30)
class UserHistoryApiIntegrationTests {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final LocalDateTime TIME = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
	@LocalServerPort private int port;
	@Autowired private UserRepository users;
	@Autowired private QuestRepository quests;
	@Autowired private SubmissionRepository submissions;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JwtEncoder encoder;
	@Autowired private EntityManagerFactory entityManagerFactory;
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private User owner;
	private User other;

	@BeforeEach
	void fixtures() {
		owner = users.save(User.create(UUID.randomUUID() + "@example.com", "hash", "owner"));
		other = users.save(User.create(UUID.randomUUID() + "@example.com", "hash", "other"));
	}

	@Test
	void authoredQuestsIncludeEveryStatusAndOnlyPassedCounts() throws Exception {
		// Given
		var zero = quest(owner);
		var failedOnly = quest(owner);
		var mixed = quest(owner);
		quest(other);
		submit(failedOnly, other, VerifyStatus.FAILED);
		submit(mixed, other, VerifyStatus.FAILED);
		submit(mixed, other, VerifyStatus.PASSED);
		submit(mixed, owner, VerifyStatus.PASSED);
		jdbc.update("update quest set status = 'CLOSED' where id = ?", mixed.getId());
		// When
		var body = list("quests?userId=" + other.getId(), owner);
		// Then
		assertEquals(3, body.size());
		assertEquals(mixed.getId(), body.get(0).get("questId").asLong());
		assertEquals("CLOSED", body.get(0).get("status").asText());
		assertEquals(2, body.get(0).get("passedCount").asLong());
		assertEquals(failedOnly.getId(), body.get(1).get("questId").asLong());
		assertEquals(0, body.get(1).get("passedCount").asLong());
		assertEquals(zero.getId(), body.get(2).get("questId").asLong());
		assertEquals(0, body.get(2).get("passedCount").asLong());
		assertEquals("OPEN", body.get(2).get("status").asText());
		assertEquals(Set.of("questId", "questTitle", "questType", "status", "passedCount", "createdAt"),
			body.get(0).propertyNames());
		assertEquals("quest title", body.get(0).get("questTitle").asText());
		assertEquals("GENERAL", body.get(0).get("questType").asText());
		assertEquals(TIME, LocalDateTime.parse(body.get(0).get("createdAt").asText()));
	}

	@Test
	void passedHistoryUsesQuestLocationAndSubmissionPhotoAndTimeAcrossClosedQuests() throws Exception {
		// Given
		var quest = quest(other);
		var passed = submit(quest, owner, VerifyStatus.PASSED);
		submit(quest, owner, VerifyStatus.FAILED);
		submit(quest, other, VerifyStatus.PASSED);
		jdbc.update("update quest set status = 'CLOSED' where id = ?", quest.getId());
		jdbc.update("update submission set photo_url = ?, created_at = ? where id = ?",
			"/uploads/submission/history.jpg", TIME.plusDays(1), passed.getId());
		// When
		var body = list("submissions?userId=" + other.getId(), owner);
		// Then
		assertEquals(1, body.size());
		var item = body.get(0);
		assertEquals(Set.of("submissionId", "questId", "questTitle", "placeName", "lat", "lng", "photoUrl", "createdAt"),
			item.propertyNames());
		assertEquals(passed.getId(), item.get("submissionId").asLong());
		assertEquals(quest.getId(), item.get("questId").asLong());
		assertEquals("quest title", item.get("questTitle").asText());
		assertEquals("quest place", item.get("placeName").asText());
		assertEquals(35.8714, item.get("lat").asDouble());
		assertEquals(128.6014, item.get("lng").asDouble());
		assertEquals("https://photos.example.test/uploads/submission/history.jpg", item.get("photoUrl").asText());
		assertEquals(TIME.plusDays(1), LocalDateTime.parse(item.get("createdAt").asText()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"quests", "submissions"})
	void listsOrderByNewestTimestampThenDescendingId(String resource) throws Exception {
		// Given: the lowest ID is newest; the other two share an older timestamp.
		var first = quest(owner);
		var second = quest(owner);
		var third = quest(owner);
		long firstId = first.getId(), secondId = second.getId(), thirdId = third.getId();
		String table = "quest", idField = "questId";
		if (resource.equals("submissions")) {
			firstId = submit(first, owner, VerifyStatus.PASSED).getId();
			secondId = submit(second, owner, VerifyStatus.PASSED).getId();
			thirdId = submit(third, owner, VerifyStatus.PASSED).getId();
			table = "submission";
			idField = "submissionId";
		}
		jdbc.update("update " + table + " set created_at = ? where id = ?", TIME.plusDays(1), firstId);
		// When
		var body = list(resource, owner);
		// Then
		assertEquals(3, body.size());
		assertEquals(firstId, body.get(0).get(idField).asLong());
		assertEquals(thirdId, body.get(1).get(idField).asLong());
		assertEquals(secondId, body.get(2).get(idField).asLong());
		if (resource.equals("submissions")) assertTrue(body.get(0).get("photoUrl").isNull());
	}

	@ParameterizedTest
	@ValueSource(strings = {"quests", "submissions"})
	void emptyUserCannotSeeAnotherUsersData(String resource) throws Exception {
		// Given
		var quest = quest(other);
		submit(quest, other, VerifyStatus.PASSED);
		// When
		var body = list(resource + "?userId=" + other.getId(), owner);
		// Then
		assertEquals(JSON.readTree("[]"), body);
	}

	@ParameterizedTest
	@CsvSource({"quests, ''", "quests, malformed", "submissions, ''", "submissions, malformed"})
	void missingAndMalformedTokensAreUnauthorized(String resource, String token) throws Exception {
		// Given / When
		var response = get(resource, token);
		// Then
		assertEquals(401, response.statusCode());
		assertEquals("UNAUTHORIZED", JSON.readTree(response.body()).get("code").asText());
	}

	@ParameterizedTest
	@CsvSource({"quests, 2", "submissions, 1"})
	void listsUseConstantQueryCountForMultipleQuests(String resource, long expectedQueries) throws Exception {
		// Given
		for (int i = 0; i < 5; i++) submit(quest(owner), owner, VerifyStatus.PASSED);
		var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();
		try {
			// When
			var body = list(resource, owner);
			// Then
			assertEquals(5, body.size());
			assertEquals(expectedQueries, statistics.getPrepareStatementCount());
		} finally {
			statistics.setStatisticsEnabled(false);
		}
	}

	private Quest quest(User author) {
		var quest = quests.save(Quest.create(author, "quest place", "address", 35.8714, 128.6014,
			"memory", "/uploads/quest/reference.jpg", null, QuestType.GENERAL, "quest title", "description", 2));
		jdbc.update("update quest set created_at = ? where id = ?", TIME, quest.getId());
		return quest;
	}

	private Submission submit(Quest quest, User performer, VerifyStatus status) {
		var submission = submissions.save(Submission.create(quest, performer, "greeting", null, status, null));
		jdbc.update("update submission set created_at = ? where id = ?", TIME, submission.getId());
		return submission;
	}

	private JsonNode list(String resource, User user) throws Exception {
		var claims = JwtClaimsSet.builder().subject(user.getId().toString())
			.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
		var token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
		var response = get(resource, token.getTokenValue());
		assertEquals(200, response.statusCode());
		return JSON.readTree(response.body());
	}

	private HttpResponse<String> get(String resource, String token) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/users/me/" + resource))
			.timeout(Duration.ofSeconds(10)).GET();
		if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
