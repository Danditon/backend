package com.danditon.submission.dto;

import com.danditon.submission.entity.VerifyStatus;

public record SubmissionResultResponse(Long submissionId, VerifyStatus verifyStatus, Double similarity,
	String message) {
}
