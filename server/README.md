# Bethany's Bakery API (S1)

## Prerequisites
- Java 21
- Docker + Docker Compose (for local stack / Testcontainers)

## Run tests
```bash
cd server
./gradlew test
```

## Run local stack
```bash
cd infra
docker compose -f compose.dev.yml up --build
# or: ./scripts/dev.sh
```
API: http://localhost:8080/api/v1/config

## Environment variables
See `../infra/.env.example`.
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_MAX`, `DB_POOL_MIN`
- `BAKERY_NAME`, `BAKERY_TIMEZONE`, `BAKERY_PICKUP_ADDRESS`, `BAKERY_MAPS_URL`, `BAKERY_SUPPORT_EMAIL`
- `ORDER_CUTOFF_HOURS`

## Package layout
- `config` — configuration properties and web config
- `common.error` — RFC 9457 problem handling
- `common.web` — request ID filter / web plumbing
- `common.audit` — audit (future phases)
- `security` — Spring Security baseline
- `domain` — domain/entities (future phases)
- `api.publicapi` — public endpoints (`GET /api/v1/config`)
