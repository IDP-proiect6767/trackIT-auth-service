package com.trackit.auth.service;

import com.trackit.auth.dto.ChangePasswordRequest;
import com.trackit.auth.dto.UpdateProfileRequest;
import com.trackit.auth.dto.UserResponse;
import com.trackit.auth.exception.InvalidPasswordException;
import com.trackit.auth.exception.UserAlreadyExistsException;
import com.trackit.auth.exception.UserNotFoundException;
import com.trackit.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    public UserResponse getCurrentUser(String username) {
        return userRepository.findByUsername(username)
                .map(u -> new UserResponse(u.getId(), u.getUsername(), u.getEmail(),
                        u.getRole().name(), u.isEnabled(), u.getCreatedAt()))
                .orElseThrow(() -> new UserNotFoundException("User not found: " + username));
    }

    @Transactional
    public void changePassword(String username, ChangePasswordRequest req) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + username));

        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash()))
            throw new InvalidPasswordException();

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);
        refreshTokenService.revokeAllForUser(user.getId());
    }

    @Transactional
    public UserResponse updateProfile(String username, UpdateProfileRequest req) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + username));

        if (req.email() != null && !req.email().equals(user.getEmail())) {
            if (userRepository.existsByEmail(req.email()))
                throw new UserAlreadyExistsException("Email already in use");
            user.setEmail(req.email());
        }

        userRepository.save(user);
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(),
                user.getRole().name(), user.isEnabled(), user.getCreatedAt());
    }
}
