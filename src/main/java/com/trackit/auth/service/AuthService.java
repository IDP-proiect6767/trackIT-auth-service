package com.trackit.auth.service;

import com.trackit.auth.config.JwtProperties;
import com.trackit.auth.domain.RefreshToken;
import com.trackit.auth.domain.Role;
import com.trackit.auth.domain.User;
import com.trackit.auth.dto.*;
import com.trackit.auth.exception.*;
import com.trackit.auth.repository.UserRepository;
import com.trackit.auth.security.JwtService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;
    private final MeterRegistry meterRegistry;

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_DURATION_SECONDS = 15 * 60;

    private Counter registrationsTotal;
    private Counter loginsSuccess;
    private Counter tokensRefreshed;
    private Counter logoutsTotal;

    @PostConstruct
    void initMetrics() {
        registrationsTotal = meterRegistry.counter("auth_registrations_total");
        loginsSuccess      = meterRegistry.counter("auth_logins_success_total");
        tokensRefreshed    = meterRegistry.counter("auth_tokens_refreshed_total");
        logoutsTotal       = meterRegistry.counter("auth_logouts_total");
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.username()))
            throw new UserAlreadyExistsException("Username already taken");
        if (userRepository.existsByEmail(req.email()))
            throw new UserAlreadyExistsException("Email already registered");

        User user = User.builder()
                .username(req.username())
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .role(Role.USER)
                .enabled(true)
                .failedLoginAttempts(0)
                .build();
        userRepository.save(user);
        registrationsTotal.increment();
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByUsername(req.username())
                .orElseThrow(() -> {
                    loginFailure("unknown_user");
                    return new InvalidCredentialsException();
                });

        Instant now = Instant.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            loginFailure("locked");
            throw new AccountLockedException(user.getLockedUntil());
        }

        if (!user.isEnabled()) {
            loginFailure("disabled");
            throw new AccountDisabledException();
        }

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            loginFailure("bad_password");
            int attempts = user.getFailedLoginAttempts() + 1;
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                user.setFailedLoginAttempts(0);
                user.setLockedUntil(now.plusSeconds(LOCK_DURATION_SECONDS));
                userRepository.save(user);
                throw new AccountLockedException(user.getLockedUntil());
            }
            user.setFailedLoginAttempts(attempts);
            userRepository.save(user);
            throw new InvalidCredentialsException();
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        loginsSuccess.increment();
        return buildAuthResponse(user);
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        RefreshToken old = refreshTokenService.validateAndConsume(rawRefreshToken);
        User user = old.getUser();
        String accessToken = jwtService.generateAccessToken(user);
        String newRefresh = refreshTokenService.issue(user);
        tokensRefreshed.increment();
        return new TokenPair(accessToken, newRefresh, "Bearer",
                jwtProperties.getAccessTtlSeconds(), jwtProperties.getRefreshTtlSeconds());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
        logoutsTotal.increment();
    }

    public ValidationResponse validate(String accessToken) {
        return jwtService.tryParse(accessToken).map(claims -> new ValidationResponse(
                true,
                Long.parseLong(claims.getSubject()),
                claims.get("username", String.class),
                claims.get("role", String.class),
                claims.getExpiration().toInstant().getEpochSecond()
        )).orElse(new ValidationResponse(false, null, null, null, null));
    }

    private void loginFailure(String reason) {
        meterRegistry.counter("auth_logins_failure_total", "reason", reason).increment();
    }

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user);
        TokenPair tokens = new TokenPair(accessToken, refreshToken, "Bearer",
                jwtProperties.getAccessTtlSeconds(), jwtProperties.getRefreshTtlSeconds());
        UserResponse userResponse = new UserResponse(user.getId(), user.getUsername(),
                user.getEmail(), user.getRole().name(), user.isEnabled(), user.getCreatedAt());
        return new AuthResponse(tokens, userResponse);
    }
}
