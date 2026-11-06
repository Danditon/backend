package com.danditon.quest.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.danditon.quest.dto.CreateQuestRequest;
import com.danditon.quest.dto.QuestDetailResponse;
import com.danditon.quest.dto.PreviewRequest;
import com.danditon.quest.dto.PreviewResponse;
import com.danditon.quest.dto.QuestIdResponse;
import com.danditon.quest.service.QuestPreviewService;
import org.springframework.web.bind.annotation.RequestBody;
import com.danditon.quest.dto.QuestSummaryResponse;
import com.danditon.quest.entity.QuestType;
import com.danditon.quest.service.QuestService;
import com.danditon.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/quests")
@RequiredArgsConstructor
public class QuestController {

	private final QuestService questService;
	private final QuestPreviewService questPreviewService;

	@PostMapping("/preview")
	public PreviewResponse preview(@RequestBody @Valid PreviewRequest request) {
		return questPreviewService.preview(request);
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public QuestIdResponse create(@AuthenticationPrincipal Jwt jwt,
		@RequestPart("request") @Valid CreateQuestRequest request,
		@RequestPart(value = "photo", required = false) MultipartFile photo) {
		return questService.create(CurrentUser.id(jwt), request, photo);
	}

	@GetMapping
	public List<QuestSummaryResponse> list(@AuthenticationPrincipal Jwt jwt,
		@RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
		@RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
		@RequestParam(required = false) @Min(1) Integer radius,
		@RequestParam(required = false) QuestType type,
		@RequestParam(required = false) @Min(1) @Max(3) Integer difficulty) {
		return questService.list(CurrentUser.id(jwt), lat, lng, radius, type, difficulty);
	}

	@GetMapping("/{questId}")
	public QuestDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable Long questId) {
		return questService.detail(CurrentUser.id(jwt), questId);
	}

}
