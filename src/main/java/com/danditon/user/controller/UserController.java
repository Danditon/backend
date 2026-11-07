package com.danditon.user.controller;

import java.util.List;

import com.danditon.security.CurrentUser;
import com.danditon.user.dto.MyQuestResponse;
import com.danditon.user.dto.MySubmissionResponse;
import com.danditon.user.dto.UserProfileResponse;
import com.danditon.user.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

	private final UserService users;

	public UserController(UserService users) {
		this.users = users;
	}

	@GetMapping("/me")
	public UserProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
		return users.profile(CurrentUser.id(jwt));
	}

	@GetMapping("/me/quests")
	public List<MyQuestResponse> myQuests(@AuthenticationPrincipal Jwt jwt) {
		return users.myQuests(CurrentUser.id(jwt));
	}

	@GetMapping("/me/submissions")
	public List<MySubmissionResponse> mySubmissions(@AuthenticationPrincipal Jwt jwt) {
		return users.mySubmissions(CurrentUser.id(jwt));
	}
}
