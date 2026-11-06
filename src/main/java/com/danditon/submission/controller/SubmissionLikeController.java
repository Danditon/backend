package com.danditon.submission.controller;

import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.danditon.submission.dto.SubmissionLikeResponse;
import com.danditon.submission.service.SubmissionService;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/submissions")
@RequiredArgsConstructor
public class SubmissionLikeController {

	private final SubmissionService submissionService;

	@PatchMapping("/{submissionId}/like")
	@Operation(summary = "수행 좋아요 토글", description = "인증된 모든 사용자가 호출할 수 있습니다. "
		+ "등록자만 좋아요 버튼을 표시하는 제한은 프론트엔드에서 적용합니다. "
		+ "호출마다 isLiked를 반전하며 수행이 없으면 404 NOT_FOUND를 반환합니다.")
	public SubmissionLikeResponse toggleLike(@PathVariable Long submissionId) {
		return submissionService.toggleLike(submissionId);
	}
}
