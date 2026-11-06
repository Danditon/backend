package com.danditon.submission.controller;

import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.danditon.submission.dto.SubmissionLikeResponse;
import com.danditon.submission.service.SubmissionService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/submissions")
@RequiredArgsConstructor
public class SubmissionLikeController {

	private final SubmissionService submissionService;

	@PatchMapping("/{submissionId}/like")
	public SubmissionLikeResponse toggleLike(@PathVariable Long submissionId) {
		return submissionService.toggleLike(submissionId);
	}
}
