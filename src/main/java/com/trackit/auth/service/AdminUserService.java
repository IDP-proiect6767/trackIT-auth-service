package com.trackit.auth.service;

import com.trackit.auth.domain.Role;
import com.trackit.auth.domain.User;
import com.trackit.auth.dto.AdminUpdateUserRequest;
import com.trackit.auth.dto.PageResponse;
import com.trackit.auth.dto.UserResponse;
import com.trackit.auth.exception.UserNotFoundException;
import com.trackit.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;

    public PageResponse<UserResponse> listUsers(int page, int size, String search) {
        var pageable = PageRequest.of(page, size, Sort.by("id"));
        Page<User> result = (search != null && !search.isBlank())
                ? userRepository.findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                        search, search, pageable)
                : userRepository.findAll(pageable);

        List<UserResponse> content = result.getContent().stream()
                .map(this::toResponse).toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    public UserResponse getUser(Long id) {
        return toResponse(load(id));
    }

    @Transactional
    public UserResponse updateUser(Long id, AdminUpdateUserRequest req) {
        User user = load(id);

        boolean changed = false;
        if (req.enabled() != null && req.enabled() != user.isEnabled()) {
            user.setEnabled(req.enabled());
            changed = true;
        }
        if (req.role() != null) {
            Role newRole = Role.valueOf(req.role());
            if (newRole != user.getRole()) {
                user.setRole(newRole);
                changed = true;
            }
        }

        if (changed) {
            userRepository.save(user);
            refreshTokenService.revokeAllForUser(user.getId());
        }
        return toResponse(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = load(id);

        if (user.getRole() == Role.ADMIN && user.isEnabled()) {
            long enabledAdmins = userRepository.countByRoleAndEnabled(Role.ADMIN, true);
            if (enabledAdmins <= 1)
                throw new IllegalStateException("Cannot delete the last enabled admin");
        }

        user.setEnabled(false);
        userRepository.save(user);
        refreshTokenService.revokeAllForUser(user.getId());
    }

    private User load(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + id));
    }

    private UserResponse toResponse(User u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getEmail(),
                u.getRole().name(), u.isEnabled(), u.getCreatedAt());
    }
}
