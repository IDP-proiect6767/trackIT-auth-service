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
