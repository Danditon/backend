package com.danditon.quest.dto;

import com.danditon.quest.entity.QuestType;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateQuestRequest(
	@NotNull QuestType questType,
	@NotBlank String placeName,
	@NotBlank String address,
	@NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
	@NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
	@NotBlank String longingText,
	@NotBlank String questTitle,
	@NotBlank String questDescription,
	@NotNull @Min(1) @Max(3) Integer difficulty) {
}
