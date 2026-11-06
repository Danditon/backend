package com.danditon.submission.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.danditon.common.embedding.EmbeddingClient;
import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;
import com.danditon.common.geo.GeoUtils;
import com.danditon.common.storage.FileCategory;
import com.danditon.common.storage.FileStorage;
import com.danditon.quest.entity.Quest;
import com.danditon.quest.entity.QuestType;
import com.danditon.quest.repository.QuestRepository;
import com.danditon.submission.dto.CreateSubmissionRequest;
import com.danditon.submission.dto.SubmissionListResponse;
import com.danditon.submission.dto.SubmissionListResponse.ViewerRole;
import com.danditon.submission.dto.SubmissionResultResponse;
import com.danditon.submission.entity.Submission;
import com.danditon.submission.entity.VerifyStatus;
import com.danditon.submission.repository.SubmissionRepository;
import com.danditon.user.entity.User;
import com.danditon.user.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SubmissionService {

	static final double ALLOWED_DISTANCE_METERS = 200;

	private final QuestRepository questRepository;
	private final SubmissionRepository submissionRepository;
	private final UserRepository userRepository;
	private final FileStorage fileStorage;
	private final EmbeddingClient embeddingClient;
	// 11/6 임계값 테스트 후 확정. 환경 변수 EMBEDDING_SIMILARITY_THRESHOLD로 덮어쓴다
	private final double similarityThreshold;

	public SubmissionService(QuestRepository questRepository, SubmissionRepository submissionRepository,
		UserRepository userRepository, FileStorage fileStorage, EmbeddingClient embeddingClient,
		@Value("${embedding.similarity-threshold:0.75}") double similarityThreshold) {
		this.questRepository = questRepository;
		this.submissionRepository = submissionRepository;
		this.userRepository = userRepository;
		this.fileStorage = fileStorage;
		this.embeddingClient = embeddingClient;
		this.similarityThreshold = similarityThreshold;
	}

	// 검증 순서: 필수값 → 본인 퀘스트 → 이미 성공 → 위치 200m → (IMAGE) 임베딩 유사도
	// 외부 HTTP 호출 동안 DB 트랜잭션을 잡고 있지 않도록 메서드 전체에는 @Transactional을 걸지 않는다.
	public SubmissionResultResponse create(Long userId, Long questId, CreateSubmissionRequest request,
		MultipartFile photo) {
		Quest quest = questRepository.findById(questId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		boolean hasPhoto = photo != null && !photo.isEmpty();
		if (quest.getQuestType() == QuestType.IMAGE && !hasPhoto) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "사진 퀘스트는 수행 사진이 필요해요");
		}
		if (quest.getAuthor().getId().equals(userId)) {
			throw new BusinessException(ErrorCode.SELF_QUEST);
		}
		if (submissionRepository.existsByQuestIdAndPerformerIdAndVerifyStatus(questId, userId, VerifyStatus.PASSED)) {
			throw new BusinessException(ErrorCode.ALREADY_PASSED);
		}
		double distance = GeoUtils.distanceMeters(request.lat(), request.lng(), quest.getLat(), quest.getLng());
		if (distance > ALLOWED_DISTANCE_METERS) {
			throw new BusinessException(ErrorCode.OUT_OF_RANGE);
		}
		User performer = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

		String photoPath = hasPhoto ? fileStorage.save(photo, FileCategory.SUBMISSION) : null;
		try {
			Double similarity = null;
			VerifyStatus verifyStatus = VerifyStatus.PASSED;
			if (quest.getQuestType() == QuestType.IMAGE) {
				if (quest.getPhotoEmbedding() == null) {
					log.error("IMAGE 퀘스트에 기준 임베딩이 없음: questId={}", questId);
					throw new BusinessException(ErrorCode.AI_ERROR);
				}
				similarity = EmbeddingClient.similarity(quest.getPhotoEmbedding(), embeddingClient.embed(photo));
				verifyStatus = similarity >= similarityThreshold ? VerifyStatus.PASSED : VerifyStatus.FAILED;
			}
			Submission submission = submissionRepository.save(
				Submission.create(quest, performer, request.greeting(), photoPath, verifyStatus, similarity));
			String message = verifyStatus == VerifyStatus.PASSED ? "안부가 전달됐어요"
				: "사진이 기준 사진과 달라요. 같은 구도로 다시 찍어 보세요";
			return new SubmissionResultResponse(submission.getId(), verifyStatus, similarity, message);
		} catch (RuntimeException e) {
			fileStorage.delete(photoPath);
			throw e;
		}
	}

	@Transactional(readOnly = true)
	public SubmissionListResponse list(Long userId, Long questId) {
		Quest quest = questRepository.findById(questId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		ViewerRole role;
		List<Submission> submissions;
		if (quest.getAuthor().getId().equals(userId)) {
			role = ViewerRole.AUTHOR;
			submissions = submissionRepository.findByQuestIdAndVerifyStatusOrderByCreatedAtDesc(questId, VerifyStatus.PASSED);
		} else if (submissionRepository.existsByQuestIdAndPerformerIdAndVerifyStatus(questId, userId, VerifyStatus.PASSED)) {
			role = ViewerRole.COMPLETED;
			submissions = submissionRepository.findByQuestIdAndVerifyStatusOrderByCreatedAtDesc(questId, VerifyStatus.PASSED);
		} else {
			role = ViewerRole.PERFORMER;
			submissions = submissionRepository.findByQuestIdAndPerformerIdOrderByCreatedAtDesc(questId, userId);
		}

		List<SubmissionListResponse.Item> items = submissions.stream()
			.map(s -> new SubmissionListResponse.Item(s.getId(),
				new SubmissionListResponse.Performer(s.getPerformer().getId(), s.getPerformer().getNickname()),
				s.getGreeting(), fileStorage.toUrl(s.getPhotoUrl()), s.getVerifyStatus(), s.getSimilarity(),
				Boolean.TRUE.equals(s.getIsLiked()), s.getCreatedAt()))
			.toList();
		return new SubmissionListResponse(role, items);
	}

}
