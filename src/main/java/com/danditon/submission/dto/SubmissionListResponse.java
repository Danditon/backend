package com.danditon.submission.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.danditon.submission.entity.VerifyStatus;

public record SubmissionListResponse(ViewerRole viewerRole, List<Item> submissions) {

	public enum ViewerRole {
		AUTHOR, COMPLETED, PERFORMER
	}

	public record Performer(Long userId, String nickname) {
	}

	public record Item(
		Long submissionId,
		Performer performer,
		String greeting,
		String photoUrl,
		VerifyStatus verifyStatus,
		Double similarity,
		boolean isLiked,
		LocalDateTime createdAt) {
	}
}
