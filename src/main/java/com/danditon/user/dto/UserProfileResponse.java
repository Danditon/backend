package com.danditon.user.dto;

import java.time.LocalDateTime;

public record UserProfileResponse(Long userId, String email, String nickname, LocalDateTime createdAt) {
}
