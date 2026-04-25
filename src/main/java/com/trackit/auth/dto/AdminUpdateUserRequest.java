package com.trackit.auth.dto;

import jakarta.validation.constraints.Pattern;

public record AdminUpdateUserRequest(
        Boolean enabled,
        @Pattern(regexp = "USER|ADMIN", message = "role must be USER or ADMIN") String role
) {}
