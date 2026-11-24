# S1 — Foundation and Infrastructure

- Status: implemented (follow-ups routed)
- Follow-ups: [S8 review follow-up register](S8-review-followups.md#follow-up-register-all-routed-none-implemented-here)
- Depends on: none
- Contract: `docs/api/openapi.yaml` → `getPublicConfig`
- Next: S2

## Goal

Create a runnable, testable Spring Boot API with PostgreSQL, Flyway, OpenAPI, consistent errors, structured logging, local Docker Compose, and the project skeleton every later phase extends.

No business features beyond public config and health.

## Non-goals

- Authentication.
- Catalog, menus, orders.
- Notifications.
- Production Cloudflare/mTLS setup.
- Backup automation.

## Deliverables

### Server project

- Gradle Kotlin DSL, Java 21 toolchain.
- Spring Boot 3.5 with:
  - web
  - validation
  - data-jpa
  - security
  - actuator
  - flyway
  - postgresql driver
  - springdoc-openapi
  - test, testcontainers, junit
- Package root: `com.bethanysbakery.api`.
- Packages:
  - `config`
  - `common.error`
  - `common.web`
  - `common.audit`
  - `security`
  - `domain`
  - `api.publicapi`
- Profiles: `local`, `staging`, `prod`, `test`.
- Config via environment variables with `@ConfigurationProperties` and validation.
- `application-local.yml` enables SQL logging and OpenAPI UI.
- `application-prod.yml` disables SQL logging and docs UI.

### Database

- Flyway migration `V1__foundation.sql`:
  - `CREATE EXTENSION IF NOT EXISTS citext;`
  - `CREATE EXTENSION IF NOT EXISTS pgcrypto;`
- Hikari pool configured from env.
- UTC session timezone. Application timezone `America/Denver` used for date math only.

### Error model

- RFC 9457 `application/problem+json` for all errors.
- Fields: `type`, `title`, `status`, `detail`, `instance`, `code`, `requestId`, `errors[]`.
- `code` is stable and machine-readable:
  - `VALIDATION_ERROR`
  - `NOT_FOUND`
  - `METHOD_NOT_ALLOWED`
  - `UNSUPPORTED_MEDIA_TYPE`
  - `INTERNAL_ERROR`
  - `RATE_LIMITED`
- Bean validation errors map to `errors[{field,message}]`.
- Never leak stack traces, SQL, or secrets.

### Web plumbing

- `X-Request-Id` filter: accept a safe inbound ID or generate a UUID; put in MDC, response header, and problem details.
- JSON logs in `staging`/`prod`, human-readable in `local`.
- Log fields: timestamp, level, logger, message, requestId, method, path, status, durationMs.
- Request body logging disabled.
- Actuator:
  - `/actuator/health/liveness`
  - `/actuator/health/readiness`
  - `/actuator/info`
- Security baseline:
  - stateless for now; no default form login/Basic.
  - deny all except public config, actuator health/info, and OpenAPI docs in `local`.
  - security headers filter: `X-Content-Type-Options`, `Referrer-Policy`, frame denial.
  - CORS deny all. Same-origin only.

### API

- `GET /api/v1/config` → `PublicConfig`.
- Values from env:
  - `BAKERY_NAME`
  - `BAKERY_TIMEZONE=America/Denver`
  - `BAKERY_PICKUP_ADDRESS`
  - `BAKERY_MAPS_URL`
  - `BAKERY_SUPPORT_EMAIL`
  - `ORDER_CUTOFF_HOURS=12`

### Local infrastructure

- `infra/compose.dev.yml`:
  - `postgres:16-alpine` with named volume, healthcheck, port `5432` only on localhost.
  - `api` built from `server/Dockerfile`, depends on healthy postgres, port `8080`.
- `server/Dockerfile`: multi-stage Gradle build (Alpine build stage), non-root glibc JRE runtime (`eclipse-temurin:21-jre`); glibc required by the S3 WebP writer JNI library.
- `server/.dockerignore`.
- `infra/.env.example` with every variable.
- `infra/scripts/dev.sh` convenience wrapper.
- Root `.gitignore`: Gradle, build outputs, env files, IDE, media, secrets, node modules.

### Documentation

- `server/README.md`: run tests, run local stack, env vars, package layout.
- `infra/README.md`: local compose only in this phase.

## Ordered tasks

1. Create Gradle project and Spring Boot application.
2. Add configuration properties, profiles, and env validation.
3. Add Flyway baseline and PostgreSQL compose service.
4. Implement Problem Details exception handling and validation mapping.
5. Implement request ID filter, logging config, and security headers.
6. Implement public config endpoint.
7. Add springdoc and confirm checked-in OpenAPI still matches.
8. Add Dockerfile, compose file, env example, scripts, gitignore.
9. Add Testcontainers base test and initial tests.
10. Write README and handoff notes.

## Acceptance criteria

- Given a clean checkout, when `cd server && ./gradlew test` runs, then all tests pass.
- Given `docker compose -f infra/compose.dev.yml up --build`, when postgres and api are healthy, then `GET http://localhost:8080/api/v1/config` returns 200 with bakery fields.
- Given a missing route, when requested, then the response is `application/problem+json` with `code=NOT_FOUND`, `requestId`, and no stack trace.
- Given an invalid config value such as a blank `BAKERY_NAME` in prod, when the app starts, then startup fails with a clear validation message.
- Given a request, when it reaches the API, then the response includes `X-Request-Id` and logs include the same ID.
- Given an empty database, when the API starts, then Flyway applies `V1` successfully.
- Given `local` profile, when opening `/v3/api-docs`, then OpenAPI is served. In `prod`, docs UI is disabled.

## Test plan

- Context loads with Testcontainers PostgreSQL.
- Flyway migration applies and `citext` exists.
- Public config endpoint returns configured values.
- Validation error shape and field list.
- Not found error shape.
- Request ID generated and propagated.
- Security headers present.
- Prod config validation rejects blank required values.
- Docker image builds.

## Security checklist

- [ ] No secrets in git or default config.
- [ ] Postgres not exposed beyond localhost in dev.
- [ ] Problem responses do not include stack traces.
- [ ] Security headers present.
- [ ] CORS denied.
- [ ] Actuator exposure limited to health/info.
- [ ] Runtime container non-root.
- [ ] `.env` ignored by git.

## Quality gate

This phase is not complete until `/phase-gate S1` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

All acceptance criteria pass, tests are green, OpenAPI references the implemented config endpoint, and `infra/README.md` explains local startup.

## Handoff notes

- S2 adds Spring Security filters, so the S1 `SecurityFilterChain` will be extended, not replaced blindly.
- S2 adds `V2__auth.sql`; do not edit `V1` after merge.
- S6 adds real notification providers; S1 has none.
- S7 productionizes Compose and Cloudflare; S1 only provides local compose.

## Gap analysis and deferrals

| Severity | Evidence | Action | Owner |
|---|---|---|---|
| Medium | `application.yml` / `compose.dev.yml` include local-default DB credentials (`bakery`/`bakery`) and compose fallbacks for convenience. S7 requires prod startup to reject weak/default secrets. | **Deferred.** Local-only defaults acceptable for S1; must be hardened/validated in prod profile. | S7 (`docs/specs/server/S7-hardening-deploy.md`) |
| Low | `server/Dockerfile` installs system Gradle when `gradle-wrapper.jar` is absent, reducing build reproducibility. Wrapper jar is present in repo state but fallback remains. | **Deferred.** Prefer wrapper-only build and pin/check wrapper in CI-like checks if added later. | S7 / build hardening |
| Low | JSON log appender uses pattern escaping, not a dedicated JSON encoder; highly unusual control chars could still produce malformed JSON. | **Mitigated in S1** with stronger escaping; consider logstash-logback encoder if structured logging needs grow. | S7 observability hardening (optional) |
| Low | Rate limiting behavior (`RATE_LIMITED`/429, `Retry-After`) not implemented by S1 endpoints; error model mapping added. | **Deferred.** Actual limits implemented by S2/S5/S7 as specified. | S2, S5, S7 |
| Low | `request completed` access-style log emitted by `RequestIdFilter`; MDC fields include status/duration for that event. No separate access-log framework configured. | **Recorded.** Meets S1 field requirement; revisit if centralized access logging added. | S7 |
