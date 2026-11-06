package com.danditon.quest.dto;

import java.util.List;

public record PreviewResponse(
	String questTitle,
	String questDescription,
	int difficulty,
	String difficultyReason,
	List<String> keywords) {
}
