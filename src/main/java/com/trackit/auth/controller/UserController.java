package com.trackit.auth.controller;

import com.trackit.auth.dto.ChangePasswordRequest;
import com.trackit.auth.dto.UpdateProfileRequest;
import com.trackit.auth.dto.UserResponse;
import com.trackit.auth.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/me/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal UserDetails principal,
                               @Valid @RequestBody ChangePasswordRequest req) {
        userService.changePassword(principal.getUsername(), req);
    }

    @PatchMapping("/me")
    public UserResponse updateProfile(@AuthenticationPrincipal UserDetails principal,
                                      @Valid @RequestBody UpdateProfileRequest req) {
        return userService.updateProfile(principal.getUsername(), req);
    }
}
