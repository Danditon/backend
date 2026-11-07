package com.danditon.user.dto;

import java.time.LocalDateTime;

public record MySubmissionResponse(Long submissionId, Long questId, String questTitle, String placeName,
	double lat, double lng, String photoUrl, LocalDateTime createdAt) {
}
