package com.trackit.auth.dto;

public record TokenPair(
        String accessToken,
        String refreshToken,
        String tokenType,
        long accessExpiresIn,
        long refreshExpiresIn
) {}
