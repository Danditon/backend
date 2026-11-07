package com.danditon;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.danditon.quest.entity.Quest;
import com.danditon.quest.entity.QuestType;
import com.danditon.quest.repository.QuestRepository;
import com.danditon.submission.entity.Submission;
import com.danditon.submission.entity.VerifyStatus;
import com.danditon.submission.repository.SubmissionRepository;
import com.danditon.user.entity.User;
import com.danditon.user.repository.UserRepository;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:like-http-${random.uuid};DB_CLOSE_DELAY=0;LOCK_TIMEOUT=5000",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@DirtiesContext
@Timeout(30)
class SubmissionLikeApiIntegrationTests {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String UNAUTHORIZED = "{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요해요\"}";
	@LocalServerPort private int port;
	@Autowired private UserRepository users;
	@Autowired private QuestRepository quests;
	@Autowired private SubmissionRepository submissions;
	@Autowired private JwtEncoder encoder;
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private Quest quest;
	private User performer;
	private Long submissionId;
	private String authorToken;
	private String performerToken;

	@BeforeEach
	void fixtures() {
		var author = users.save(User.create(UUID.randomUUID() + "@example.com", "hash", "author"));
		performer = users.save(User.create(UUID.randomUUID() + "@example.com", "hash", "performer"));
		quest = createQuest(author);
		submissionId = submissions.save(Submission.create(quest, performer, "hello", null, VerifyStatus.PASSED, null)).getId();
		authorToken = token(author.getId().toString());
		performerToken = token(performer.getId().toString());
	}

	@Test
	void authorTogglesFalseTrueFalse() throws Exception {
		assertFalse(submissions.findById(submissionId).orElseThrow().getIsLiked());
		// When
		var first = patch(submissionId.toString(), authorToken);
		var second = patch(submissionId.toString(), authorToken);
		// Then
		assertLike(first, true);
		assertLike(second, false);
		assertFalse(submissions.findById(submissionId).orElseThrow().getIsLiked());
	}

	@Test
	void performerCannotLikeSubmission() throws Exception {
		var response = patch(submissionId.toString(), performerToken);

		assertForbidden(response);
		assertFalse(submissions.findById(submissionId).orElseThrow().getIsLiked());
		assertListedLike(false);
	}

	@Test
	void authorOfAnotherQuestCannotUnlikeSubmission() throws Exception {
		var otherAuthor = users.save(User.create(UUID.randomUUID() + "@example.com", "hash", "other author"));
		createQuest(otherAuthor);
		assertLike(patch(submissionId.toString(), authorToken), true);

		var response = patch(submissionId.toString(), token(otherAuthor.getId().toString()));

		assertForbidden(response);
		assertTrue(submissions.findById(submissionId).orElseThrow().getIsLiked());
		assertListedLike(true);
	}

	@Test
	void failedSubmissionCanBeLikedByAuthor() throws Exception {
		// Given
		submissionId = submissions.save(Submission.create(quest, performer, "retry", null, VerifyStatus.FAILED, 0.2)).getId();
		// When
		var response = patch(submissionId.toString(), authorToken);
		// Then
		assertLike(response, true);
		assertTrue(submissions.findById(submissionId).orElseThrow().getIsLiked());
	}

	@Test
	void missingSubmissionReturnsNotFound() throws Exception {
		// Given / When
		var response = patch(Long.toString(Long.MAX_VALUE), performerToken);
		// Then
		assertEquals(404, response.statusCode());
		assertEquals("NOT_FOUND", JSON.readTree(response.body()).get("code").asText());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "malformed"})
	void unauthorizedRequestHasExactResponseWithoutMutation(String token) throws Exception {
		// Given / When
		var response = patch(submissionId.toString(), token);
		// Then
		assertUnauthorizedWithoutMutation(response);
	}

	@Test
	void signedNonnumericSubjectReturnsUnauthorizedWithoutMutation() throws Exception {
		var response = patch(submissionId.toString(), token("not-a-user-id"));

		assertUnauthorizedWithoutMutation(response);
	}

	@ParameterizedTest
	@ValueSource(strings = {"not-a-number", "9223372036854775808"})
	void malformedPathReturnsBadRequestWithoutMutation(String id) throws Exception {
		// Given / When
		var response = patch(id, performerToken);
		// Then
		assertEquals(400, response.statusCode());
		assertEquals("INVALID_INPUT", JSON.readTree(response.body()).get("code").asText());
		assertFalse(submissions.findById(submissionId).orElseThrow().getIsLiked());
	}

	@Test
	void submissionListReflectsEachCommittedToggle() throws Exception {
		// Given
		assertListedLike(false);
		// When / Then: re-read API #9 after each successful mutation
		assertLike(patch(submissionId.toString(), authorToken), true);
		assertListedLike(true);
		assertLike(patch(submissionId.toString(), authorToken), false);
		assertListedLike(false);
	}

	@RepeatedTest(value = 10, name = "{displayName} {currentRepetition}/{totalRepetitions}")
	void concurrentAuthorRequestsEachToggleOnce() throws Exception {
		// Given
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> concurrentPatch(ready, start));
			var second = executor.submit(() -> concurrentPatch(ready, start));
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			// When
			start.countDown();
			var firstResponse = first.get(15, TimeUnit.SECONDS);
			var secondResponse = second.get(15, TimeUnit.SECONDS);
			// Then
			assertEquals(200, firstResponse.statusCode());
			assertEquals(200, secondResponse.statusCode());
			boolean firstLiked = JSON.readTree(firstResponse.body()).get("isLiked").asBoolean();
			assertLike(firstResponse, firstLiked);
			assertLike(secondResponse, !firstLiked);
			assertFalse(submissions.findById(submissionId).orElseThrow().getIsLiked());
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	private HttpResponse<String> concurrentPatch(CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		assertTrue(start.await(5, TimeUnit.SECONDS));
		return patch(submissionId.toString(), authorToken);
	}

	private void assertForbidden(HttpResponse<String> response) {
		assertEquals(403, response.statusCode());
		assertEquals(JSON.readTree("{\"code\":\"FORBIDDEN\",\"message\":\"권한이 없어요\"}"), JSON.readTree(response.body()));
	}

	private void assertUnauthorizedWithoutMutation(HttpResponse<String> response) {
		assertEquals(401, response.statusCode());
		assertEquals(UNAUTHORIZED, response.body());
		assertFalse(submissions.findById(submissionId).orElseThrow().getIsLiked());
	}

	private void assertListedLike(boolean expected) throws Exception {
		var response = send(HttpRequest.newBuilder(uri("/api/quests/" + quest.getId() + "/submissions"))
			.header("Authorization", "Bearer " + authorToken).GET());
		assertEquals(200, response.statusCode());
		var item = JSON.readTree(response.body()).get("submissions").get(0);
		assertEquals(submissionId, item.get("submissionId").asLong());
		assertEquals(expected, item.get("isLiked").asBoolean());
	}

	private void assertLike(HttpResponse<String> response, boolean expected) {
		assertEquals(200, response.statusCode());
		var body = JSON.readTree(response.body());
		assertEquals(Set.of("submissionId", "isLiked"), body.propertyNames());
		assertEquals(submissionId, body.get("submissionId").asLong());
		assertTrue(body.get("isLiked").isBoolean());
		assertEquals(expected, body.get("isLiked").asBoolean());
	}

	private HttpResponse<String> patch(String id, String token) throws Exception {
		var request = HttpRequest.newBuilder(uri("/api/submissions/" + id + "/like"))
			.method("PATCH", HttpRequest.BodyPublishers.noBody());
		if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
		return send(request);
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

	private Quest createQuest(User author) {
		return quests.save(Quest.create(author, "place", "address", 35.8714, 128.6014,
			"memory", null, null, QuestType.GENERAL, "title", "description", 2));
	}

	private String token(String subject) {
		var claims = JwtClaimsSet.builder().subject(subject)
			.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}
}
