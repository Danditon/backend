package com.danditon.submission.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.danditon.security.CurrentUser;
import com.danditon.submission.dto.CreateSubmissionRequest;
import com.danditon.submission.dto.SubmissionListResponse;
import com.danditon.submission.dto.SubmissionResultResponse;
import com.danditon.submission.service.SubmissionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/quests/{questId}/submissions")
@RequiredArgsConstructor
public class SubmissionController {

	private final SubmissionService submissionService;

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public SubmissionResultResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable Long questId,
		@RequestPart("request") @Valid CreateSubmissionRequest request,
		@RequestPart(value = "photo", required = false) MultipartFile photo) {
		return submissionService.create(CurrentUser.id(jwt), questId, request, photo);
	}

	@GetMapping
	public SubmissionListResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long questId) {
		return submissionService.list(CurrentUser.id(jwt), questId);
	}

}
