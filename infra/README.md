# Infrastructure

## Local compose (S1)
Starts PostgreSQL 16 and the API for local development.

```bash
docker compose -f compose.dev.yml up --build
```
- Postgres exposed on `127.0.0.1:5432` only.
- API on `http://localhost:8080`.
- Config via env vars; defaults in `compose.dev.yml`, example in `.env.example`.

Production/Cloudflare hardening is added in S7.
