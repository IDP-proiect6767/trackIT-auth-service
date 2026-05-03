# TrackIT Auth Service — Full Implementation Plan

## Project context

- **Stack:** Java 21, Spring Boot 3.4.x, Maven, PostgreSQL 16, Flyway, Spring Security, JJWT 0.12.x, Testcontainers, Lombok.
- **Role:** Microservice that handles user registration, login, JWT issuance/validation, refresh token rotation, self-service password/profile changes, and admin user management. Has its own DB (`auth_db`). Other services call `POST /api/v1/auth/validate` to verify access tokens.
- **Deployment target:** Docker image, runs on Docker Swarm worker labeled `role=auth`.
- **Repo:** `trackIT-auth-service`.
- **Docker Hub:** `florinionita/trackit-auth-service`.

## Conventions

- **Package base:** `com.trackit.auth`
- **API prefix:** `/api/v1`
- **JWT alg:** HS256, secret from env var `AUTH_JWT_SECRET` (min 32 bytes).
  - **Access token TTL:** 15 minutes. Claims: `sub` (userId), `username`, `role`, `iat`, `exp`, `iss`, `type=access`.
  - **Refresh token:** opaque random string (256 bits, base64url-encoded), stored hashed (SHA-256) in DB. TTL 7 days. Rotation on every refresh.
- **Password hashing:** BCrypt, strength 12.
- **Account lockout:** 5 consecutive failed logins → lock for 15 minutes.
- **DB schema:** `auth` (not `public`).
- **Config style:** `application.yml` with profiles (`default`, `docker`, `test`). All secrets via env vars.

## High-level endpoint map (target state at end of plan)

| Method | Path                                  | Auth   | Role  |
|--------|---------------------------------------|--------|-------|
| POST   | `/api/v1/auth/register`               | none   | —     |
| POST   | `/api/v1/auth/login`                  | none   | —     |
| POST   | `/api/v1/auth/refresh`                | none*  | —     |
| POST   | `/api/v1/auth/logout`                 | none*  | —     |
| POST   | `/api/v1/auth/validate`               | none   | —     |
| GET    | `/api/v1/auth/me`                     | bearer | any   |
| POST   | `/api/v1/users/me/change-password`    | bearer | any   |
| PATCH  | `/api/v1/users/me`                    | bearer | any   |
| GET    | `/api/v1/admin/users`                 | bearer | ADMIN |
| GET    | `/api/v1/admin/users/{id}`            | bearer | ADMIN |
| PATCH  | `/api/v1/admin/users/{id}`            | bearer | ADMIN |
| DELETE | `/api/v1/admin/users/{id}`            | bearer | ADMIN |

\* refresh/logout authenticate via the refresh token in the request body, not via Bearer header.

---

## Phase 0 — Project bootstrap

**Goal:** Empty Spring Boot project that builds and runs.

1. Generate `pom.xml`: parent `spring-boot-starter-parent:3.4.x`, Java 21, group `com.trackit`, artifact `auth-service`, version `0.1.0-SNAPSHOT`.
2. Dependencies:
   - `spring-boot-starter-web`
   - `spring-boot-starter-security`
   - `spring-boot-starter-data-jpa`
   - `spring-boot-starter-validation`
   - `spring-boot-starter-actuator`
   - `org.postgresql:postgresql` (runtime)
   - `org.flywaydb:flyway-core` + `flyway-database-postgresql`
   - `io.jsonwebtoken:jjwt-api:0.12.6`, `jjwt-impl:0.12.6` (runtime), `jjwt-jackson:0.12.6` (runtime)
   - `io.micrometer:micrometer-registry-prometheus`
   - `org.projectlombok:lombok` (provided)
   - `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.x`
   - **Test:** `spring-boot-starter-test`, `spring-security-test`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`
3. `AuthServiceApplication.java` with `@SpringBootApplication`.
4. `application.yml` minimal: `spring.application.name=auth-service`, `server.port=8081`.
5. `.gitignore` for Java/Maven/IntelliJ.
6. `mvn clean verify` must pass.

**Acceptance:** App starts on port 8081. `mvn verify` green.

---

## Phase 1 — Database schema (Flyway)

**Goal:** All tables we'll need throughout the project, created reproducibly.

1. `application.yml` datasource + JPA + Flyway sections:
   ```yaml
   spring:
     datasource:
       url: ${AUTH_DB_URL:jdbc:postgresql://localhost:5432/auth_db}
       username: ${AUTH_DB_USER:auth_user}
       password: ${AUTH_DB_PASSWORD:auth_pass}
     jpa:
       hibernate.ddl-auto: validate
       properties.hibernate.default_schema: auth
       open-in-view: false
     flyway:
       schemas: auth
       default-schema: auth
       baseline-on-migrate: true
   ```
2. `V1__init_auth_schema.sql`:
   - `CREATE SCHEMA IF NOT EXISTS auth;`
   - `auth.users`:
     - `id BIGSERIAL PRIMARY KEY`
     - `username VARCHAR(64) NOT NULL UNIQUE`
     - `email VARCHAR(255) NOT NULL UNIQUE`
     - `password_hash VARCHAR(255) NOT NULL`
     - `role VARCHAR(20) NOT NULL CHECK (role IN ('USER','ADMIN'))`
     - `enabled BOOLEAN NOT NULL DEFAULT TRUE`
     - `failed_login_attempts INT NOT NULL DEFAULT 0`
     - `locked_until TIMESTAMPTZ NULL`
     - `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
     - `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
   - `auth.refresh_tokens`:
     - `id BIGSERIAL PRIMARY KEY`
     - `user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE`
     - `token_hash VARCHAR(64) NOT NULL UNIQUE` (SHA-256 hex = 64 chars)
     - `expires_at TIMESTAMPTZ NOT NULL`
     - `revoked_at TIMESTAMPTZ NULL`
     - `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
   - Indexes:
     - `CREATE INDEX idx_refresh_tokens_user_id ON auth.refresh_tokens(user_id);`
     - `CREATE INDEX idx_refresh_tokens_expires_at ON auth.refresh_tokens(expires_at);`
3. `V2__seed_admin.sql`: insert one admin user (username `admin`, email `admin@trackit.local`, BCrypt hash for `admin123`, role `ADMIN`). Add SQL comment `-- DEV ONLY: change password before deploying anywhere public`.

**Acceptance:** With local Postgres running, `mvn spring-boot:run` applies both migrations. `\d auth.users` and `\d auth.refresh_tokens` show expected columns.

---

## Phase 2 — Domain & persistence

**Goal:** JPA entities + repositories.

1. `domain.Role` enum: `USER`, `ADMIN`.
2. `domain.User` entity mapped to `auth.users`. `@CreationTimestamp`, `@UpdateTimestamp`. Lombok `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`.
3. `domain.RefreshToken` entity mapped to `auth.refresh_tokens`. `@ManyToOne` to `User` (LAZY). Same Lombok annotations.
4. `repository.UserRepository extends JpaRepository<User, Long>`:
   - `Optional<User> findByUsername(String)`
   - `Optional<User> findByEmail(String)`
   - `boolean existsByUsername(String)`
   - `boolean existsByEmail(String)`
   - `Page<User> findAll(Pageable)` (inherited; just confirm pagination works in Phase 9)
5. `repository.RefreshTokenRepository extends JpaRepository<RefreshToken, Long>`:
   - `Optional<RefreshToken> findByTokenHash(String)`
   - `@Modifying @Query("UPDATE RefreshToken rt SET rt.revokedAt = CURRENT_TIMESTAMP WHERE rt.user.id = :userId AND rt.revokedAt IS NULL")` `int revokeAllForUser(Long userId)`
   - `@Modifying @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoff")` `int deleteExpired(Instant cutoff)` (used by scheduled cleanup later)

**Acceptance:** Hibernate validates schema on startup with `ddl-auto: validate` (no errors).

---

## Phase 3 — DTOs

**Goal:** Request/response records under `dto.*` (Java records).

**Auth:**
- `RegisterRequest(@NotBlank @Size(min=3,max=64) @Pattern(regexp="^[a-zA-Z0-9_.-]+$") String username, @NotBlank @Email @Size(max=255) String email, @NotBlank @Size(min=8,max=72) String password)`
- `LoginRequest(@NotBlank String username, @NotBlank String password)`
- `RefreshRequest(@NotBlank String refreshToken)`
- `LogoutRequest(@NotBlank String refreshToken)`
- `ValidateRequest(@NotBlank String token)`
- `TokenPair(String accessToken, String refreshToken, String tokenType, long accessExpiresIn, long refreshExpiresIn)` — `tokenType` always `"Bearer"`.
- `AuthResponse(TokenPair tokens, UserResponse user)`
- `ValidationResponse(boolean valid, Long userId, String username, String role, Long expiresAt)` — when `valid=false`, all other fields null.

**User:**
- `UserResponse(Long id, String username, String email, String role, boolean enabled, Instant createdAt)`
- `ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min=8,max=72) String newPassword)`
- `UpdateProfileRequest(@Email @Size(max=255) String email)` — only email is mutable by the user themselves.

**Admin:**
- `AdminUpdateUserRequest(Boolean enabled, String role)` — both nullable; null means "don't change". Validate `role` is `USER` or `ADMIN` if non-null.
- `PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages)` — generic page wrapper to avoid leaking Spring's `Page` shape.

**Errors:**
- `ErrorResponse(String code, String message, Instant timestamp, Map<String,String> details)` — `details` optional, used for field validation errors. Stable `code` strings: `VALIDATION_FAILED`, `USER_ALREADY_EXISTS`, `INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`, `ACCOUNT_DISABLED`, `INVALID_REFRESH_TOKEN`, `INVALID_PASSWORD`, `USER_NOT_FOUND`, `FORBIDDEN`, `INTERNAL_ERROR`.

---

## Phase 4 — JWT layer

**Goal:** Issue and parse access tokens.

1. `config.JwtProperties` with `@ConfigurationProperties(prefix="auth.jwt")`:
   - `secret` (String, min 32 chars; validate in `@PostConstruct`)
   - `accessTtlSeconds` (long, default 900)
   - `refreshTtlSeconds` (long, default 604800)
   - `issuer` (String, default `"trackit-auth"`)
2. `application.yml`:
   ```yaml
   auth:
     jwt:
       secret: ${AUTH_JWT_SECRET:change-me-change-me-change-me-change-me}
       access-ttl-seconds: ${AUTH_JWT_ACCESS_TTL:900}
       refresh-ttl-seconds: ${AUTH_JWT_REFRESH_TTL:604800}
       issuer: trackit-auth
   ```
3. `security.JwtService`:
   - `String generateAccessToken(User user)` — builds JWT with `sub=userId.toString()`, `username`, `role`, `iat`, `exp`, `iss`, `type="access"`.
   - `Jws<Claims> parse(String token)` — throws `JwtException` subclasses on invalid/expired/wrong-sig.
   - `Optional<Claims> tryParse(String token)` — wraps parse, returns empty on any exception.
   - Use `Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))` cached as a `SecretKey` field.
4. **Unit tests** for `JwtService`:
   - generate→parse roundtrip recovers all claims
   - expired token rejected (build a token with `exp` in the past via reflection or by setting `accessTtlSeconds=-1` in test props)
   - tampered payload rejected
   - wrong-signature token rejected (sign with different key, try to parse)
   - secret too short throws on context startup

**Acceptance:** All `JwtService` unit tests pass. Tokens decodable on jwt.io with the dev secret.

---

## Phase 5 — Spring Security configuration

**Goal:** Stateless filter chain that authenticates `Authorization: Bearer <token>` on protected routes, with method-level security for admin endpoints.

1. `security.CustomUserDetailsService implements UserDetailsService`:
   - Loads user by username.
   - Throws `UsernameNotFoundException` if missing.
   - Returns `org.springframework.security.core.userdetails.User` with authority `ROLE_<role>`, `enabled` from entity, `accountNonLocked` from `locked_until` (if null or in the past → not locked).
2. `security.JwtAuthenticationFilter extends OncePerRequestFilter`:
   - Skip if no `Authorization` header or doesn't start with `Bearer `.
   - `jwtService.tryParse(token)` → if empty, continue chain.
   - Build `UsernamePasswordAuthenticationToken` with principal=`username`, no credentials, authority=`ROLE_<role>` from claims. **Do not hit the DB** — claims are trusted (they're signed). Set details and put in `SecurityContextHolder`.
3. `config.SecurityConfig`:
   - `@EnableWebSecurity`, `@EnableMethodSecurity(prePostEnabled = true)`.
   - `SecurityFilterChain`:
     - CSRF disabled, CORS configured (permissive in dev — allow all origins; tighten via property for prod).
     - Session policy `STATELESS`.
     - Authorize:
       - Permit: `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`, `POST /api/v1/auth/validate`, `GET /actuator/health/**`, `GET /actuator/info`, `GET /actuator/prometheus`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`.
       - All else: `authenticated()`.
     - Add `JwtAuthenticationFilter` before `UsernamePasswordAuthenticationFilter`.
     - Custom `AuthenticationEntryPoint` and `AccessDeniedHandler` — both write JSON `ErrorResponse` (codes `INVALID_CREDENTIALS` and `FORBIDDEN` respectively).
   - Beans: `PasswordEncoder` = `new BCryptPasswordEncoder(12)`, `AuthenticationManager` from `AuthenticationConfiguration`.

**Acceptance:** Request to `/api/v1/auth/me` without token returns 401 JSON. Request with valid token (manually generated in a test or via Phase 7's login) reaches a placeholder controller.

---

## Phase 6 — Exception handling & cross-cutting concerns

**Goal:** All errors return consistent `ErrorResponse` JSON. Establish exception classes used by later phases.

1. Custom exceptions in `exception.*` (all extend `RuntimeException`):
   - `UserAlreadyExistsException`
   - `InvalidCredentialsException`
   - `AccountLockedException(Instant unlockAt)`
   - `AccountDisabledException`
   - `InvalidRefreshTokenException`
   - `InvalidPasswordException` (for change-password)
   - `UserNotFoundException`
2. `controller.GlobalExceptionHandler` (`@RestControllerAdvice`):
   - `MethodArgumentNotValidException` → 400, `code=VALIDATION_FAILED`, `details` map field→message.
   - `ConstraintViolationException` → 400, same pattern.
   - `UserAlreadyExistsException` → 409, `code=USER_ALREADY_EXISTS`.
   - `InvalidCredentialsException` → 401, `code=INVALID_CREDENTIALS`.
   - `AccountLockedException` → 423 (Locked), `code=ACCOUNT_LOCKED`, include `unlockAt` ISO-8601 in `details`.
   - `AccountDisabledException` → 403, `code=ACCOUNT_DISABLED`.
   - `InvalidRefreshTokenException` → 401, `code=INVALID_REFRESH_TOKEN`.
   - `InvalidPasswordException` → 400, `code=INVALID_PASSWORD`.
   - `UserNotFoundException` → 404, `code=USER_NOT_FOUND`.
   - `AccessDeniedException` → 403, `code=FORBIDDEN`.
   - `Exception` (fallback) → 500, log full stack trace, return generic `code=INTERNAL_ERROR, message="Internal server error"` (do not leak details).

**Acceptance:** Hitting any nonexistent protected route → 401 JSON with our shape. Hitting a route with malformed JSON → 400 JSON with our shape.

---

## Phase 7 — Auth core (register, login, validate, refresh, logout)

**Goal:** Full token lifecycle.

### 7a. `service.RefreshTokenService`

- `String issue(User user)`:
  - Generate 32 random bytes via `SecureRandom`, base64url-encode → this is the "raw" token returned to the client.
  - Hash with SHA-256 → hex → store as `RefreshToken(userId, tokenHash, expiresAt=now+refreshTtl)`.
  - Return raw token.
- `RefreshToken validateAndConsume(String rawToken)`:
  - Hash, look up by hash. Throw `InvalidRefreshTokenException` if not found, or `revokedAt != null`, or `expiresAt < now`.
  - Mark `revokedAt = now`. Return entity.
- `void revoke(String rawToken)`:
  - Hash, look up, set `revokedAt = now` if found and not already revoked. Silent no-op if not found (don't leak whether the token existed).
- `void revokeAllForUser(Long userId)`: bulk revoke (used on password change).

### 7b. `service.AuthService`

- `AuthResponse register(RegisterRequest req)`:
  - Reject if `existsByUsername` or `existsByEmail` → `UserAlreadyExistsException`.
  - Save new `User` with role `USER`, hashed password, `enabled=true`.
  - Issue access + refresh tokens. Increment `auth_registrations_total` counter (Phase 10).
  - Return `AuthResponse` (201 mapped at controller).
- `AuthResponse login(LoginRequest req)`:
  - Load user by username. If absent → `InvalidCredentialsException` (don't leak existence).
  - If `lockedUntil != null && lockedUntil > now` → `AccountLockedException(lockedUntil)`.
  - If `!enabled` → `AccountDisabledException`.
  - If password doesn't match → increment `failed_login_attempts`, if reaches 5 set `locked_until = now + 15min` and reset counter to 0, save, throw `InvalidCredentialsException` (or `AccountLockedException` if just locked). Increment `auth_logins_failure_total`.
  - On success: reset `failed_login_attempts=0`, `locked_until=null`, save. Issue tokens. Increment `auth_logins_success_total`. Return `AuthResponse`.
- `TokenPair refresh(String rawRefreshToken)`:
  - `validateAndConsume` (which also revokes the old one — rotation).
  - Issue new access + new refresh for the same user. Return.
- `void logout(String rawRefreshToken)`:
  - `revoke`. Always returns 204 No Content (success even if token unknown — see "silent no-op" above).
- `ValidationResponse validate(String accessToken)`:
  - `jwtService.tryParse(token)` — if empty → `ValidationResponse(false, null, null, null, null)`.
  - On success → extract `sub`, `username`, `role`, `exp` from claims. Return populated.

### 7c. `controller.AuthController` (`@RequestMapping("/api/v1/auth")`)

- `POST /register` → `201`, body `AuthResponse`.
- `POST /login` → `200`, body `AuthResponse`.
- `POST /refresh` → `200`, body `TokenPair`.
- `POST /logout` → `204`, no body.
- `POST /validate` → `200`, body `ValidationResponse` (always 200 — `valid` field signals result; this is what other services consume).
- `GET /me` → `200`, body `UserResponse`. Authenticated only. Read principal username from `SecurityContextHolder`, look up user from DB. Throw `UserNotFoundException` if missing (shouldn't happen but be safe).

**Acceptance:** End-to-end curl flow works:
1. `POST /register` → 201 + tokens
2. `POST /login` (same creds) → 200 + tokens
3. `POST /validate` (access token) → 200, `valid=true`
4. `GET /me` (Bearer access token) → 200
5. `POST /refresh` (refresh token) → 200 + new pair; old refresh now rejected
6. `POST /logout` (refresh token) → 204; subsequent `/refresh` with same token → 401
7. 5 wrong-password attempts → account locked (next attempt → 423)

---

## Phase 8 — User self-management

**Goal:** Authenticated users manage their own credentials/profile.

### 8a. `service.UserService`

- `UserResponse getCurrentUser(String username)` — used by `/me`. (You may have already done this inline in Phase 7; refactor here.)
- `void changePassword(String username, ChangePasswordRequest req)`:
  - Load user by username.
  - If `currentPassword` doesn't match → `InvalidPasswordException`.
  - Hash and save new password. **Revoke all refresh tokens for this user** (force re-login on other devices).
- `UserResponse updateProfile(String username, UpdateProfileRequest req)`:
  - Load user.
  - If `req.email != null` and differs from current: check no other user has it (`existsByEmail`) → throw `UserAlreadyExistsException` if yes; otherwise update.
  - Save and return updated `UserResponse`.

### 8b. `controller.UserController` (`@RequestMapping("/api/v1/users")`)

- `POST /me/change-password` → `204`. Authenticated.
- `PATCH /me` → `200`, body `UserResponse`. Authenticated.
- `GET /me` is already on `AuthController`; do **not** duplicate. (Or move it here and update Phase 7's docs/tests — your call, just be consistent.)

**Acceptance:** Change password → old refresh tokens rejected, new login with new password works. PATCH email → reflected in subsequent `/me`.

---

## Phase 9 — Admin user management

**Goal:** Admin-only endpoints for managing the user base.

### 9a. `service.AdminUserService`

- `PageResponse<UserResponse> listUsers(int page, int size, String search)`:
  - Use `PageRequest.of(page, size, Sort.by("id"))`.
  - If `search` is non-blank, filter by `username ILIKE %search%` OR `email ILIKE %search%` (use a Specification or a custom JPQL query). Otherwise plain `findAll(pageable)`.
  - Map to `PageResponse<UserResponse>`.
- `UserResponse getUser(Long id)` — `UserNotFoundException` if absent.
- `UserResponse updateUser(Long id, AdminUpdateUserRequest req)`:
  - Load. If `req.enabled != null` set it. If `req.role != null` validate and set.
  - **Side effect:** if disabling or changing role, revoke all refresh tokens for that user (so the change takes effect immediately).
  - Save, return.
- `void deleteUser(Long id)`:
  - **Soft delete approach:** set `enabled=false` and revoke all refresh tokens. Don't actually delete the row (preserves FK integrity if other services hold user IDs). Document this in the controller's javadoc.
  - Refuse to delete the last admin: count users with `role=ADMIN AND enabled=true`; if `<= 1` and the target is one of them, throw an `IllegalStateException` mapped to 409.

### 9b. `controller.AdminController` (`@RequestMapping("/api/v1/admin/users")`)

All methods annotated `@PreAuthorize("hasRole('ADMIN')")` (rely on method-level security from Phase 5).

- `GET /` (query params: `page=0`, `size=20`, `search=`) → `200`, body `PageResponse<UserResponse>`.
- `GET /{id}` → `200`, body `UserResponse`.
- `PATCH /{id}` (body `AdminUpdateUserRequest`) → `200`, body `UserResponse`.
- `DELETE /{id}` → `204`.

**Acceptance:** Login as `admin`/`admin123` → admin endpoints accessible. Login as a regular user → admin endpoints return 403 with `code=FORBIDDEN`. Disabling a user → their existing access tokens still work until expiry (15min) but they can't refresh, and `/me` will start returning 403 after `enabled` is checked. Deleting the last enabled admin → 409.

---

## Phase 10 — Observability

**Goal:** Prometheus scraping works with custom metrics.

1. `application.yml` actuator section:
   ```yaml
   management:
     endpoints.web.exposure.include: health,info,prometheus,metrics
     endpoint.health.probes.enabled: true
     metrics.tags.application: ${spring.application.name}
   ```
2. Custom counters via `MeterRegistry`, registered as beans or inline in services:
   - `auth_registrations_total`
   - `auth_logins_success_total`
   - `auth_logins_failure_total{reason}` — tag `reason` ∈ {`bad_password`, `locked`, `disabled`, `unknown_user`}.
   - `auth_tokens_refreshed_total`
   - `auth_logouts_total`
   - `auth_password_changes_total`
   - `auth_admin_actions_total{action}` — tag `action` ∈ {`update`, `delete`}.
3. Verify `GET /actuator/prometheus` returns metrics including the custom counters. Manually trigger a few flows and watch counters increment.

---

## Phase 11 — Scheduled cleanup

**Goal:** Don't let `refresh_tokens` grow unbounded.

1. `config.SchedulingConfig` with `@EnableScheduling`.
2. `service.RefreshTokenCleanupJob`:
   - `@Scheduled(cron = "0 0 3 * * *")` (daily at 03:00 server time).
   - Calls `refreshTokenRepository.deleteExpired(Instant.now().minus(7, DAYS))` — i.e., delete tokens that expired more than 7 days ago (keeps a small grace window for forensics).
   - Log how many were deleted.
3. Make schedule configurable via `auth.cleanup.cron` property (default `0 0 3 * * *`).

**Acceptance:** Insert an expired-long-ago token in a test, trigger the job manually (call the method directly in a test), verify deletion.

---

## Phase 12 — Tests

**Goal:** Decent coverage of all service/controller paths.

### Unit tests

- `JwtServiceTest` (already done in Phase 4)
- `RefreshTokenServiceTest` (with `@DataJpaTest` + Testcontainers, or pure mocks for hashing/validation logic)
- `AuthServiceTest` (Mockito): register success/duplicate-username/duplicate-email; login success/wrong-password/locked/disabled/unknown-user; lockout triggers at 5 failures; refresh rotation works; refresh with revoked token throws; validate happy/expired/tampered.
- `UserServiceTest`: change-password success/wrong-current; update-profile email change/duplicate.
- `AdminUserServiceTest`: list with/without search; update enables/disables and revokes tokens; delete soft-deletes and revokes; delete-last-admin refused.

### Integration tests

Under `src/test/java/.../it/`. `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `@Testcontainers` with `PostgreSQLContainer` (one container reused across tests via `static` field with manual `start()`). Use `MockMvc`. Override datasource via `@DynamicPropertySource`.

- `AuthFlowIT`: register → login → validate → /me → refresh → logout → refresh-after-logout-rejected.
- `LockoutIT`: 5 wrong logins → locked → correct password rejected with 423 → after 15 min (manipulate `locked_until` directly in DB, don't sleep) → unlocked.
- `ChangePasswordIT`: change pwd → old refresh tokens rejected, new login with new pwd works.
- `AdminIT`: admin lists/updates/deletes; non-admin gets 403; delete-last-admin returns 409.
- `SecurityIT`: protected route without token → 401 with our error shape; with garbage token → 401; with token signed by wrong key → 401.

**Acceptance:** `mvn verify` runs all tests green.

---

## Phase 13 — Docker

**Goal:** Production-ready image.

1. `Dockerfile` multi-stage:
   - **Builder:** `maven:3.9-eclipse-temurin-21` → copy `pom.xml`, run `mvn -B dependency:go-offline`, copy `src/`, run `mvn -B -DskipTests package`.
   - **Runtime:** `eclipse-temurin:21-jre-alpine` → create non-root user `appuser`, copy jar to `/app/app.jar`, `EXPOSE 8081`, `HEALTHCHECK CMD wget -qO- http://localhost:8081/actuator/health || exit 1`, `USER appuser`, `ENTRYPOINT ["java","-jar","/app/app.jar"]`.
2. `.dockerignore`: `target`, `.git`, `.idea`, `*.iml`, `*.md`, `Dockerfile`, etc.
3. `docker-compose.yml` (local dev only — prod stack lives in `trackIT-infrastructure`):
   - `auth-db` (postgres:16-alpine), healthcheck `pg_isready`, named volume.
   - `auth-service` depends_on `auth-db` (`condition: service_healthy`), env vars wired up, ports `8081:8081`.
   - `pgadmin` (optional).
4. `docker compose up --build` → curl through `http://localhost:8081` works for full register/login/refresh flow.

---

## Phase 14 — CI/CD (GitHub Actions)

**Goal:** Build/test on every push, publish image on `main`.

1. `.github/workflows/ci.yml`:
   - Trigger: `push` to any branch, `pull_request` to `main`.
   - Job `build-test`: `actions/checkout@v4`, `actions/setup-java@v4` (temurin 21), `actions/cache@v4` for `~/.m2`, run `mvn -B verify`.
   - Upload surefire/failsafe reports as artifact on failure (`actions/upload-artifact@v4`).
2. `.github/workflows/release.yml`:
   - Trigger: `push` to `main`.
   - Steps: checkout, setup-java 21, build jar, `docker/setup-buildx-action@v3`, `docker/login-action@v3` with `${{ secrets.DOCKERHUB_USERNAME }}` / `${{ secrets.DOCKERHUB_TOKEN }}`, `docker/build-push-action@v5` with tags `florinionita/trackit-auth-service:latest` and `florinionita/trackit-auth-service:${{ github.sha }}`.
3. Document required GitHub secrets in `README.md`: `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`.

---

## Phase 15 — Documentation & release

**Goal:** Repo is self-explanatory and tagged.

1. `README.md`:
   - One-paragraph description (link to TrackIT umbrella project).
   - **Quick start:** `docker compose up` + curl examples for register/login/refresh/logout/validate.
   - **API reference:** the endpoint table from the top of this plan, plus link to Swagger UI at `/swagger-ui.html`.
   - **Configuration:** table of env vars (`AUTH_DB_URL`, `AUTH_DB_USER`, `AUTH_DB_PASSWORD`, `AUTH_JWT_SECRET`, `AUTH_JWT_ACCESS_TTL`, `AUTH_JWT_REFRESH_TTL`).
   - **Build & test:** `mvn verify`.
   - **Deployment:** Docker Swarm placement constraint `node.labels.role == auth`. Reference to the stack file in `trackIT-infrastructure`.
   - **GitHub secrets required for CI:** `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`.
2. Final pass on `CHANGELOG.md`: ensure `[Unreleased]` accurately reflects everything done.
3. Bump `pom.xml` version `0.1.0-SNAPSHOT` → `0.1.0`. In `CHANGELOG.md`, replace `## [Unreleased]` heading with `## [0.1.0] - YYYY-MM-DD` and add a fresh empty `## [Unreleased]` section above. Commit, tag `v0.1.0`, push tag.

---

## Versioning notes

- Pre-1.0: API may break between minors. Bump minor for new endpoints, patch for fixes.
- After Etapa 1 ships and the API is stable, cut `1.0.0`.

## Out of scope (intentionally)

- Email verification on registration (would need SMTP infra).
- Password reset via email (same reason).
- OAuth2 / social login.
- 2FA.
- Rate limiting at the app level — Kong handles this at the gateway.
- Audit log table — current Prometheus counters are sufficient for the demo.

If any of these become required later, add them as new phases here, bump the minor version, and document in `CHANGELOG.md`.
