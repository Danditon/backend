package com.danditon.quest.dto;

import com.danditon.quest.entity.QuestType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PreviewRequest(
	@NotBlank String placeName,
	@NotBlank String address,
	@NotBlank String longingText,
	@NotNull QuestType questType) {
}
