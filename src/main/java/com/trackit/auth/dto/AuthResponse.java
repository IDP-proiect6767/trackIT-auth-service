package com.trackit.auth.dto;

public record AuthResponse(
        TokenPair tokens,
        UserResponse user
) {}
