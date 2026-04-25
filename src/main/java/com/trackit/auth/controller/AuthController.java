package com.trackit.auth.controller;

import com.trackit.auth.dto.*;
import com.trackit.auth.exception.UserNotFoundException;
import com.trackit.auth.repository.UserRepository;
import com.trackit.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest req) {
        authService.logout(req.refreshToken());
    }

    @PostMapping("/validate")
    public ValidationResponse validate(@Valid @RequestBody ValidateRequest req) {
        return authService.validate(req.token());
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserDetails principal) {
        String username = principal.getUsername();
        return userRepository.findByUsername(username)
                .map(u -> new UserResponse(u.getId(), u.getUsername(), u.getEmail(),
                        u.getRole().name(), u.isEnabled(), u.getCreatedAt()))
                .orElseThrow(() -> new UserNotFoundException("User not found: " + username));
    }
}
