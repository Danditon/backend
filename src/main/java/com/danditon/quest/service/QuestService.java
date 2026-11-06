package com.danditon.quest.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.danditon.common.embedding.EmbeddingClient;
import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;
import com.danditon.common.geo.GeoUtils;
import com.danditon.common.storage.FileCategory;
import com.danditon.common.storage.FileStorage;
import com.danditon.quest.dto.CreateQuestRequest;
import com.danditon.quest.dto.QuestDetailResponse;
import com.danditon.quest.dto.QuestDetailResponse.MyStatus;
import com.danditon.quest.dto.QuestIdResponse;
import com.danditon.quest.dto.QuestSummaryResponse;
import com.danditon.quest.entity.Quest;
import com.danditon.quest.entity.QuestStatus;
import com.danditon.quest.entity.QuestType;
import com.danditon.quest.repository.QuestRepository;
import com.danditon.submission.entity.VerifyStatus;
import com.danditon.submission.repository.SubmissionRepository;
import com.danditon.user.entity.User;
import com.danditon.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestService {

	static final int DEFAULT_RADIUS_METERS = 50_000;

	private final QuestRepository questRepository;
	private final SubmissionRepository submissionRepository;
	private final UserRepository userRepository;
	private final FileStorage fileStorage;
	private final EmbeddingClient embeddingClient;

	// 사진 저장 → (IMAGE) 임베딩 → DB 저장. 임베딩·DB 저장이 실패하면 저장한 사진 파일을 지운다.
	// 외부 HTTP 호출 동안 DB 트랜잭션을 잡고 있지 않도록 메서드 전체에는 @Transactional을 걸지 않는다.
	public QuestIdResponse create(Long userId, CreateQuestRequest request, MultipartFile photo) {
		User author = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		boolean hasPhoto = photo != null && !photo.isEmpty();
		if (request.questType() == QuestType.IMAGE && !hasPhoto) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "사진 퀘스트는 기준 사진이 필요해요");
		}

		String photoPath = hasPhoto ? fileStorage.save(photo, FileCategory.QUEST) : null;
		try {
			List<Float> embedding = request.questType() == QuestType.IMAGE ? embeddingClient.embed(photo) : null;
			Quest quest = questRepository.save(Quest.create(author, request.placeName(), request.address(),
				request.lat(), request.lng(), request.longingText(), photoPath, embedding, request.questType(),
				request.questTitle(), request.questDescription(), request.difficulty()));
			return new QuestIdResponse(quest.getId());
		} catch (RuntimeException e) {
			fileStorage.delete(photoPath);
			throw e;
		}
	}

	@Transactional(readOnly = true)
	public List<QuestSummaryResponse> list(Long userId, double lat, double lng, Integer radius,
		QuestType type, Integer difficulty) {
		int radiusMeters = radius == null ? DEFAULT_RADIUS_METERS : radius;
		return questRepository.findByStatus(QuestStatus.OPEN).stream()
			.filter(q -> type == null || q.getQuestType() == type)
			.filter(q -> difficulty == null || q.getDifficulty() == difficulty)
			.map(q -> new Object[] {q, GeoUtils.distanceMeters(lat, lng, q.getLat(), q.getLng())})
			.filter(pair -> (double) pair[1] <= radiusMeters)
			.sorted(Comparator.comparingDouble(pair -> (double) pair[1]))
			.map(pair -> {
				Quest q = (Quest) pair[0];
				return new QuestSummaryResponse(q.getId(), q.getQuestTitle(), q.getQuestType(), q.getDifficulty(),
					q.getPlaceName(), q.getLat(), q.getLng(), Math.round((double) pair[1]),
					q.getAuthor().getId().equals(userId));
			})
			.toList();
	}

	@Transactional(readOnly = true)
	public QuestDetailResponse detail(Long userId, Long questId) {
		Quest quest = questRepository.findById(questId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		MyStatus myStatus = submissionRepository.existsByQuestIdAndPerformerIdAndVerifyStatus(
			questId, userId, VerifyStatus.PASSED) ? MyStatus.PASSED
				: submissionRepository.existsByQuestIdAndPerformerId(questId, userId) ? MyStatus.FAILED
					: MyStatus.NONE;
		User author = quest.getAuthor();
		return new QuestDetailResponse(quest.getId(), quest.getQuestType(),
			new QuestDetailResponse.Author(author.getId(), author.getNickname()),
			quest.getPlaceName(), quest.getAddress(), quest.getLat(), quest.getLng(), quest.getLongingText(),
			fileStorage.toUrl(quest.getPhotoUrl()), quest.getQuestTitle(), quest.getQuestDescription(),
			quest.getDifficulty(), quest.getStatus(), author.getId().equals(userId), myStatus);
	}

}
