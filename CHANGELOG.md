# Changelog

All notable changes to the TrackIT Auth Service will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial implementation plan (`IMPLEMENTATION_PLAN.md`).
- This `CHANGELOG.md`.
- **Phase 0 — Project bootstrap**: `pom.xml` (Spring Boot 3.4.1, Java 21), `AuthServiceApplication`, minimal `application.yml` (port 8081), `.gitignore`. `mvn verify` passes.
- **Phase 1 — Database schema**: Flyway migrations `V1__init_auth_schema.sql` (creates `auth.users` and `auth.refresh_tokens` with indexes) and `V2__seed_admin.sql` (dev admin user). `application.yml` extended with datasource, JPA, and Flyway config.
- **Phase 2 — Domain & persistence**: `Role` enum, `User` and `RefreshToken` JPA entities (Lombok, `@CreationTimestamp`/`@UpdateTimestamp`), `UserRepository` and `RefreshTokenRepository` with custom queries for token revocation and cleanup.
- **Phase 3 — DTOs**: All request/response records — auth (`RegisterRequest`, `LoginRequest`, `RefreshRequest`, `LogoutRequest`, `ValidateRequest`, `TokenPair`, `AuthResponse`, `ValidationResponse`), user (`UserResponse`, `ChangePasswordRequest`, `UpdateProfileRequest`), admin (`AdminUpdateUserRequest`, `PageResponse<T>`), and `ErrorResponse` with stable error codes.
- **Phase 4 — JWT layer**: `JwtProperties` (`@ConfigurationProperties`, validates secret ≥ 32 bytes on startup), `JwtService` (generate/parse/tryParse with HS256). Maven toolchain locked to Java 21 (Bellsoft) via `.mvn/toolchains.xml` to avoid Lombok breakage under Java 25. 7 unit tests green: roundtrip, expired, tampered payload, wrong-key, tryParse empty/valid, short-secret guard.
- **Phase 5 — Spring Security**: `CustomUserDetailsService` (loads user, maps `lockedUntil` to `accountNonLocked`), `JwtAuthenticationFilter` (stateless Bearer extraction, no DB hit), `SecurityConfig` (CSRF off, stateless sessions, permit-list, JSON `AuthenticationEntryPoint`/`AccessDeniedHandler`, `BCryptPasswordEncoder(12)`).
- **Phase 7 — Auth core**: `RefreshTokenService` (issue/validateAndConsume/revoke/revokeAll, SHA-256 hashing, `SecureRandom`), `AuthService` (register, login with 5-attempt lockout, refresh with rotation, logout silent no-op, validate), `AuthController` (`/register` 201, `/login`, `/refresh`, `/logout` 204, `/validate`, `/me`).
- **Phase 6 — Exception handling**: 7 custom `RuntimeException` subclasses (`UserAlreadyExistsException`, `InvalidCredentialsException`, `AccountLockedException`, `AccountDisabledException`, `InvalidRefreshTokenException`, `InvalidPasswordException`, `UserNotFoundException`). `GlobalExceptionHandler` maps each to stable `ErrorResponse` codes and HTTP statuses (400/401/403/404/409/423/500).
- **Interaction notes**: `interaction-notes.md` added — full inter-service integration reference (validate endpoint contract, token format, error codes, env vars, Prometheus metrics, Docker networking).

<!--
Section types you can use under each release (omit empty ones):

  ### Added       — for new features.
  ### Changed     — for changes in existing functionality.
  ### Deprecated  — for soon-to-be removed features.
  ### Removed     — for now removed features.
  ### Fixed       — for any bug fixes.
  ### Security    — in case of vulnerabilities.

Workflow:
  1. Add entries under [Unreleased] as you work.
  2. When releasing, replace [Unreleased] heading with [X.Y.Z] - YYYY-MM-DD
     and start a fresh empty [Unreleased] section above it.
-->
