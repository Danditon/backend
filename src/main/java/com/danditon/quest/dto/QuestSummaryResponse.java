package com.danditon.quest.dto;

import com.danditon.quest.entity.QuestType;

public record QuestSummaryResponse(
	Long questId,
	String questTitle,
	QuestType questType,
	int difficulty,
	String placeName,
	double lat,
	double lng,
	long distance,
	boolean isMine) {
}
