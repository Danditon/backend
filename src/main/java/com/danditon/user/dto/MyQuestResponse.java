package com.danditon.user.dto;

import java.time.LocalDateTime;

import com.danditon.quest.entity.QuestStatus;
import com.danditon.quest.entity.QuestType;

public record MyQuestResponse(Long questId, String questTitle, QuestType questType,
	QuestStatus status, long passedCount, LocalDateTime createdAt) {
}
