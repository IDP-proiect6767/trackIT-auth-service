# trackIT-auth-service

Microservice responsible for user registration, login, JWT issuance/validation, refresh token rotation, self-service profile management, and admin user management. Part of the [TrackIT](../README.md) umbrella project. Other services verify access tokens by calling `POST /api/v1/auth/validate`.

## Quick start

```bash
docker compose up --build
```

Register a user:
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","email":"alice@example.com","password":"secret123"}' | jq
```

Login:
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}' | jq
```

Refresh tokens:
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/refresh \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<refresh_token>"}' | jq
```

Logout:
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/logout \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<refresh_token>"}' -w '%{http_code}'
```

Validate an access token (used by other services):
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/validate \
  -H 'Content-Type: application/json' \
  -d '{"token":"<access_token>"}' | jq
```

## API reference

| Method | Path | Auth | Role |
|--------|------|------|------|
| POST | `/api/v1/auth/register` | none | — |
| POST | `/api/v1/auth/login` | none | — |
| POST | `/api/v1/auth/refresh` | refresh token in body | — |
| POST | `/api/v1/auth/logout` | refresh token in body | — |
| POST | `/api/v1/auth/validate` | none | — |
| GET | `/api/v1/auth/me` | Bearer | any |
| POST | `/api/v1/users/me/change-password` | Bearer | any |
| PATCH | `/api/v1/users/me` | Bearer | any |
| GET | `/api/v1/admin/users` | Bearer | ADMIN |
| GET | `/api/v1/admin/users/{id}` | Bearer | ADMIN |
| PATCH | `/api/v1/admin/users/{id}` | Bearer | ADMIN |
| DELETE | `/api/v1/admin/users/{id}` | Bearer | ADMIN |

Interactive docs: [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html)

## Configuration

| Env var | Description | Default |
|---------|-------------|---------|
| `AUTH_DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/auth_db` |
| `AUTH_DB_USER` | Database username | `auth_user` |
| `AUTH_DB_PASSWORD` | Database password | `auth_pass` |
| `AUTH_JWT_SECRET` | HMAC-SHA256 signing secret (min 32 bytes) | `change-me-change-me-change-me-change-me` |
| `AUTH_JWT_ACCESS_TTL` | Access token TTL in seconds | `900` (15 min) |
| `AUTH_JWT_REFRESH_TTL` | Refresh token TTL in seconds | `604800` (7 days) |

## Build & test

```bash
mvn verify
```

Tests use Testcontainers and require a Docker daemon.

## Deployment

Docker image: `florinionita/trackit-auth-service:latest`

The service targets a Docker Swarm worker with placement constraint:
```
node.labels.role == auth
```

The production stack file lives in the `trackIT-infrastructure` repository.

## GitHub secrets required for CI

| Secret | Description |
|--------|-------------|
| `DOCKERHUB_USERNAME` | Docker Hub account username |
| `DOCKERHUB_TOKEN` | Docker Hub access token |
