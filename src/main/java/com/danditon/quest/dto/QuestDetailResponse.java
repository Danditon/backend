package com.danditon.quest.dto;

import com.danditon.quest.entity.QuestStatus;
import com.danditon.quest.entity.QuestType;

public record QuestDetailResponse(
	Long questId,
	QuestType questType,
	Author author,
	String placeName,
	String address,
	double lat,
	double lng,
	String longingText,
	String photoUrl,
	String questTitle,
	String questDescription,
	int difficulty,
	QuestStatus status,
	boolean isMine,
	MyStatus myStatus) {

	public record Author(Long userId, String nickname) {
	}

	public enum MyStatus {
		NONE, FAILED, PASSED
	}
}
