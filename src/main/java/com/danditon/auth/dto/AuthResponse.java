package com.danditon.auth.dto;

public record AuthResponse(String accessToken, Long userId, String nickname) {
}
