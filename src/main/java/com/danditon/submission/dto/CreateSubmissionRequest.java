package com.danditon.submission.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// lat·lng는 위치 재검증에만 쓰고 저장하지 않는다
public record CreateSubmissionRequest(
	@NotBlank String greeting,
	@NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
	@NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng) {
}
