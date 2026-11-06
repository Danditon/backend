package com.danditon.user.service;

import java.util.List;
import java.util.stream.Collectors;

import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;
import com.danditon.common.storage.FileStorage;
import com.danditon.quest.entity.Quest;
import com.danditon.quest.repository.QuestRepository;
import com.danditon.submission.entity.VerifyStatus;
import com.danditon.submission.repository.SubmissionRepository;
import com.danditon.submission.repository.SubmissionRepository.QuestPassedCount;
import com.danditon.user.dto.MyQuestResponse;
import com.danditon.user.dto.MySubmissionResponse;
import com.danditon.user.dto.UserProfileResponse;
import com.danditon.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

	private final UserRepository users;
	private final QuestRepository quests;
	private final SubmissionRepository submissions;
	private final FileStorage fileStorage;

	public UserService(UserRepository users, QuestRepository quests, SubmissionRepository submissions,
		FileStorage fileStorage) {
		this.users = users;
		this.quests = quests;
		this.submissions = submissions;
		this.fileStorage = fileStorage;
	}

	@Transactional(readOnly = true)
	public UserProfileResponse profile(Long userId) {
		var user = users.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		return new UserProfileResponse(user.getId(), user.getEmail(), user.getNickname(), user.getCreatedAt());
	}

	@Transactional(readOnly = true)
	public List<MyQuestResponse> myQuests(Long userId) {
		var authored = quests.findByAuthorIdOrderByCreatedAtDescIdDesc(userId);
		if (authored.isEmpty()) return List.of();
		var counts = submissions.countByQuestIdsAndStatus(authored.stream().map(Quest::getId).toList(),
			VerifyStatus.PASSED).stream()
			.collect(Collectors.toMap(QuestPassedCount::getQuestId, QuestPassedCount::getPassedCount));
		return authored.stream().map(quest -> new MyQuestResponse(quest.getId(), quest.getQuestTitle(),
			quest.getQuestType(), quest.getStatus(), counts.getOrDefault(quest.getId(), 0L), quest.getCreatedAt()))
			.toList();
	}

	@Transactional(readOnly = true)
	public List<MySubmissionResponse> mySubmissions(Long userId) {
		return submissions.findByPerformerIdAndVerifyStatusOrderByCreatedAtDescIdDesc(userId, VerifyStatus.PASSED)
			.stream().map(submission -> {
				var quest = submission.getQuest();
				return new MySubmissionResponse(submission.getId(), quest.getId(), quest.getQuestTitle(),
					quest.getPlaceName(), quest.getLat(), quest.getLng(), fileStorage.toUrl(submission.getPhotoUrl()),
					submission.getCreatedAt());
			}).toList();
	}
}
