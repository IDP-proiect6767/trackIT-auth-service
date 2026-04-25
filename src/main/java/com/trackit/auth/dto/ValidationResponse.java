package com.trackit.auth.dto;

public record ValidationResponse(
        boolean valid,
        Long userId,
        String username,
        String role,
        Long expiresAt
) {}
