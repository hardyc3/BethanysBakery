# Cloudflare Tunnel, WAF, and geo rules

One public hostname: `bakery.cherry-home.com`. Cloudflare terminates HTTPS; the
origin is plain HTTP on the internal Docker network. WAF and geo rules are
**path-scoped** — `EdgeRequestFilter` stays path-based, and no hostname is
hardcoded in application code.

Prerequisites
- Cloudflare account with `cherry-home.com`; Zero Trust tunnel token available.
- `CLOUDFLARE_TUNNEL_TOKEN` set in `/opt/bethanys-bakery/.env` (mode 600).
- No host firewall rule publishes 80/443/8080/5432; compose has no `ports:`.

Console setup
1. Zero Trust → Networks → Tunnels → Create tunnel. Copy token into `.env`.
2. Tunnel configuration → Public Hostname; add these ingresses for the **one**
   hostname (order matters — longest path first):
   - `/admin/v1` → HTTP → `api:8080`
   - `/api` → HTTP → `api:8080`
   - `/` → HTTP → `web:80`
   Reference: `infra/cloudflared/config.yml.example`.
3. DNS → record for `bakery.cherry-home.com` must be proxied (orange cloud).
4. SSL/TLS → Edge Certificates: TLS 1.2+ minimum, HSTS enabled with
   `max-age=31536000; includeSubDomains`. No origin cert or ACME artifacts.
5. Security → WAF → Managed rules: Cloudflare Managed Ruleset and OWASP Core
   Ruleset in blocking mode.
6. Security → WAF → Custom rules (all on the one hostname):
   - `US customer API only`:
     `(starts_with(http.request.uri.path, "/api/v1/") and not starts_with(http.request.uri.path, "/api/v1/webhooks/") and ip.geoip.country ne "US")` → **Block** (403).
     `/api/v1/webhooks/textbee` stays unauthenticated at the edge and is
     signature-verified at the origin. The origin applies the same
     `CF-IPCountry` check to `/api/v1/**` with webhooks exempt; health probes are
     not exposed publicly.
   - `Pair strict rate limit`:
     `(http.request.uri.path eq "/admin/v1/devices/pair")` → Rate limit 10
     requests / 15 min / IP → Block 429, `Retry-After: 900`. This is the only
     `/admin/v1/**` path exempt from mTLS (see `cloudflare-mtls-ca.md`).
   - `Admin media upload throttle`:
     `(http.request.method eq "POST" and starts_with(http.request.uri.path, "/admin/v1/media"))` → Rate limit 20 / 15 min / IP → Block 429. Origin also enforces 20/admin/hour.
7. Caching: cache only `GET /api/v1/media/{mediaId}*`; bypass `/api/v1/menu-dates*`,
   `/api/v1/orders*`, auth/CSRF, webhooks, `/admin/`, and `/actuator/`. Edge
   takedown-purge policy and its probe are routed to S10 in the S8 follow-up
   register; do not add host-scoped cache rules here.

Deploy / commands
```bash
cd /opt/bethanys-bakery
git pull --ff-only
docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/.env up -d --build
sh infra/scripts/logs.sh cloudflared
docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/.env \
  ps --format '{{.Names}} {{.Ports}}' | grep -E '0\.0\.0\.0|::' || echo 'no published ports'
```

Verification (run from a US network)
```bash
curl -fsS https://bakery.cherry-home.com/api/v1/config
curl -sS -o /dev/null -w '%{http_code}\n' -H 'CF-IPCountry: CA' \
  https://bakery.cherry-home.com/api/v1/config                       # 403 COUNTRY_BLOCKED
curl -sS -o /dev/null -w '%{http_code}\n' \
  https://bakery.cherry-home.com/api/v1/config                       # 403 when CF-IPCountry missing
curl -sS -o /dev/null -w '%{http_code}\n' -H 'CF-IPCountry: US' -H 'Origin: https://evil.example' \
  https://bakery.cherry-home.com/api/v1/auth/csrf                    # 403, no CORS
curl -sS -o /dev/null -w '%{http_code}\n' -X POST \
  https://bakery.cherry-home.com/api/v1/webhooks/textbee \
  -H 'Content-Type: application/json' -H 'X-Textbee-Signature: bad' --data '{}'   # 401 at origin, not geo-blocked
```

External-network origin probe (run from cellular/home ISP, NEVER from inside the tunnel network)
```bash
ORIGIN_IP=<lxc-public-ip>
for p in 80 443 8080 5432; do
  timeout 3 nc -zv "$ORIGIN_IP" "$p" 2>&1 || echo "$p closed/filtered"
done
```
Expected: every direct port is refused/filtered; only the Cloudflare hostname
serves traffic. Cloudflare WAF geo block must not affect provider webhooks.

Rollback
- Revert the rule in Cloudflare (save the previous rule expression first) or
  disable the rule; tunnel token rollback = put the previous token in `.env`
  and redeploy with the raw Compose commands above. Never publish host ports.

Notify
- Owner + ops on-call before tunnel/token changes; security owner for WAF/geo changes.
