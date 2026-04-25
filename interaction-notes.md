# TrackIT Auth Service — Inter-Service Interaction Notes

Everything another service (or a developer wiring up the system) needs to know to integrate with this microservice.

---

## Base URL

| Environment | URL |
|-------------|-----|
| Local dev   | `http://localhost:8081` |
| Docker Swarm | `http://auth-service:8081` (internal network) |

API prefix: `/api/v1`

---

## The only endpoint other services need to call

### `POST /api/v1/auth/validate`

Used by every downstream service (task-service, project-service, etc.) to verify an access token presented by a client.

**No authentication required** — this endpoint is public.

**Request**
```http
POST /api/v1/auth/validate
Content-Type: application/json

{
  "token": "<access_token>"
}
```

**Response — valid token** `200 OK`
```json
{
  "valid": true,
  "userId": 42,
  "username": "alice",
  "role": "USER",
  "expiresAt": 1716000000
}
```

**Response — invalid / expired token** `200 OK`
```json
{
  "valid": false,
  "userId": null,
  "username": null,
  "role": null,
  "expiresAt": null
}
```

> Always HTTP 200. Check the `valid` field. Never trust a token without calling this endpoint (or replicating the JWT secret + verification logic — don't do that).

**Roles returned:** `"USER"` or `"ADMIN"`.

---

## Full endpoint reference

### Public endpoints (no token required)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/auth/register` | Register a new user |
| `POST` | `/api/v1/auth/login` | Login, receive token pair |
| `POST` | `/api/v1/auth/refresh` | Rotate refresh token, get new pair |
| `POST` | `/api/v1/auth/logout` | Revoke refresh token |
| `POST` | `/api/v1/auth/validate` | **Validate access token** ← other services use this |

### Authenticated endpoints (Bearer access token required)

| Method | Path | Role | Description |
|--------|------|------|-------------|
| `GET`  | `/api/v1/auth/me` | any | Current user profile |
| `POST` | `/api/v1/users/me/change-password` | any | Change own password |
| `PATCH`| `/api/v1/users/me` | any | Update own profile (email) |
| `GET`  | `/api/v1/admin/users` | ADMIN | List all users (paginated) |
| `GET`  | `/api/v1/admin/users/{id}` | ADMIN | Get user by ID |
| `PATCH`| `/api/v1/admin/users/{id}` | ADMIN | Enable/disable user or change role |
| `DELETE`| `/api/v1/admin/users/{id}` | ADMIN | Soft-delete user |

---

## Token format

### Access token (JWT, HS256)

- **Header:** `Authorization: Bearer <access_token>`
- **TTL:** 15 minutes (`AUTH_JWT_ACCESS_TTL`, default 900s)
- **Algorithm:** HS256, secret from `AUTH_JWT_SECRET` env var (min 32 bytes)
- **Claims:**

| Claim | Type | Example | Notes |
|-------|------|---------|-------|
| `sub` | string | `"42"` | User ID as string |
| `username` | string | `"alice"` | |
| `role` | string | `"USER"` | `USER` or `ADMIN` |
| `type` | string | `"access"` | Always `"access"` |
| `iss` | string | `"trackit-auth"` | |
| `iat` | number | unix epoch | Issued at |
| `exp` | number | unix epoch | Expiry |

### Refresh token

- **Format:** opaque, 256-bit random, base64url-encoded
- **TTL:** 7 days (default)
- **Storage:** stored **hashed** (SHA-256) in the DB; the raw value is only ever returned to the client
- **Rotation:** every `/refresh` call revokes the old token and issues a new one
- **Never send to other services** — only the auth service handles refresh tokens

---

## Request / Response schemas

### Register `POST /api/v1/auth/register`

Request:
```json
{
  "username": "alice",        // 3–64 chars, [a-zA-Z0-9_.-]
  "email": "alice@example.com",
  "password": "secret1234"    // 8–72 chars
}
```
Response `201 Created`: [`AuthResponse`](#authresponse)

---

### Login `POST /api/v1/auth/login`

Request:
```json
{ "username": "alice", "password": "secret1234" }
```
Response `200 OK`: [`AuthResponse`](#authresponse)

---

### Refresh `POST /api/v1/auth/refresh`

Request:
```json
{ "refreshToken": "<opaque_refresh_token>" }
```
Response `200 OK`: [`TokenPair`](#tokenpair)

---

### Logout `POST /api/v1/auth/logout`

Request:
```json
{ "refreshToken": "<opaque_refresh_token>" }
```
Response `204 No Content` (always — even if token was already invalid).

---

### AuthResponse

```json
{
  "tokens": {
    "accessToken": "<jwt>",
    "refreshToken": "<opaque>",
    "tokenType": "Bearer",
    "accessExpiresIn": 900,
    "refreshExpiresIn": 604800
  },
  "user": {
    "id": 42,
    "username": "alice",
    "email": "alice@example.com",
    "role": "USER",
    "enabled": true,
    "createdAt": "2026-04-25T10:00:00Z"
  }
}
```

### TokenPair

```json
{
  "accessToken": "<jwt>",
  "refreshToken": "<opaque>",
  "tokenType": "Bearer",
  "accessExpiresIn": 900,
  "refreshExpiresIn": 604800
}
```

---

## Error response shape

All errors return this JSON body:

```json
{
  "code": "INVALID_CREDENTIALS",
  "message": "Invalid credentials",
  "timestamp": "2026-04-25T10:00:00Z",
  "details": null
}
```

Stable error codes and their HTTP status:

| Code | HTTP | When |
|------|------|------|
| `VALIDATION_FAILED` | 400 | Request body fails `@Valid` |
| `INVALID_PASSWORD` | 400 | Wrong current password on change-password |
| `INVALID_CREDENTIALS` | 401 | Wrong username/password |
| `INVALID_REFRESH_TOKEN` | 401 | Refresh token expired, revoked, or unknown |
| `ACCOUNT_LOCKED` | 423 | 5 consecutive failed logins (locked 15 min); `details.unlockAt` is ISO-8601 |
| `USER_ALREADY_EXISTS` | 409 | Username or email taken |
| `ACCOUNT_DISABLED` | 403 | Admin disabled the account |
| `FORBIDDEN` | 403 | Insufficient role |
| `USER_NOT_FOUND` | 404 | User ID does not exist |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

---

## Account lockout behaviour

After **5 consecutive failed logins** the account locks for **15 minutes**. The response is `423 Locked` with `ACCOUNT_LOCKED` code and `details.unlockAt` (ISO-8601). Correct credentials during the lockout window still return `423`. After the window the account unlocks automatically on the next login attempt.

---

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `AUTH_DB_URL` | `jdbc:postgresql://localhost:5432/auth_db` | JDBC URL |
| `AUTH_DB_USER` | `auth_user` | DB username |
| `AUTH_DB_PASSWORD` | `auth_pass` | DB password |
| `AUTH_JWT_SECRET` | *(must be set — min 32 bytes)* | HS256 signing secret |
| `AUTH_JWT_ACCESS_TTL` | `900` | Access token TTL in seconds |
| `AUTH_JWT_REFRESH_TTL` | `604800` | Refresh token TTL in seconds |

---

## Actuator / health endpoints (no auth)

| Path | Purpose |
|------|---------|
| `GET /actuator/health` | Liveness / readiness probe |
| `GET /actuator/prometheus` | Prometheus metrics scrape |

### Custom Prometheus metrics

| Metric | Labels | Description |
|--------|--------|-------------|
| `auth_registrations_total` | — | Successful registrations |
| `auth_logins_success_total` | — | Successful logins |
| `auth_logins_failure_total` | `reason` | Failed logins (`bad_password`, `locked`, `disabled`, `unknown_user`) |
| `auth_tokens_refreshed_total` | — | Token refreshes |
| `auth_logouts_total` | — | Logouts |
| `auth_password_changes_total` | — | Password changes |
| `auth_admin_actions_total` | `action` | Admin mutations (`update`, `delete`) |

---

## Swagger UI

Available at `http://localhost:8081/swagger-ui.html` when running locally.
Machine-readable OpenAPI spec: `http://localhost:8081/v3/api-docs`

---

## Docker / network notes

- Container name in Docker Compose: `auth-service`
- Internal port: `8081`
- Other services in the same Docker network call: `http://auth-service:8081/api/v1/auth/validate`
- Swarm placement constraint: `node.labels.role == auth`
- Image: `florinionita/trackit-auth-service:latest`
