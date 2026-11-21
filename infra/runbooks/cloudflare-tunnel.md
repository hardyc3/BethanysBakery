# Cloudflare Tunnel, WAF, and geo rules

Prerequisites
- Cloudflare account with `cherry-home.com`; Zero Trust tunnel token available.
- `CLOUDFLARE_TUNNEL_TOKEN` (prod) / `_STAGING` set in the corresponding `.env`.
- No host firewall rule publishes 80/443/8080/5432; compose has no `ports:`.

Console setup
1. Zero Trust → Networks → Tunnels → Create tunnel. Copy token into the env file.
2. Tunnel configuration → Public Hostname; add:
   - `bakery.cherry-home.com` → HTTP → `web:80`
   - `bakery-admin-api.cherry-home.com` → HTTP → `api:8080`
   - `bakery-pair.cherry-home.com` → HTTP → `api:8080`
   Staging uses `*-staging.cherry-home.com` hostnames with the staging token.
3. DNS → records for those hostnames must be proxied (orange cloud).
4. SSL/TLS → Edge Certificates: TLS 1.2+ minimum, HSTS enabled with `max-age=31536000; includeSubDomains`.
5. Security → WAF → Managed rules: enable Cloudflare Managed Ruleset and OWASP Core Ruleset in blocking mode.
6. Security → WAF → Custom rules (prod examples; duplicate for staging hostnames):
   - `US customer API only`:
     `(http.host eq "bakery.cherry-home.com" and starts_with(http.request.uri.path, "/api/v1/") and not starts_with(http.request.uri.path, "/api/v1/webhooks/") and ip.geoip.country ne "US")` → **Block** (403).
     Health probes under `/actuator/health/**` are not exposed on the customer hostname; app-level country filter also exempts them.
   - `Pair strict rate limit`:
     `http.host eq "bakery-pair.cherry-home.com" and http.request.uri.path eq "/admin/v1/devices/pair"` → Rate limit 10 requests / 15 min / IP → Block 429, `Retry-After: 900`. No mTLS on this hostname.
   - `Admin media upload throttle`:
     `http.host eq "bakery-admin-api.cherry-home.com" and http.request.method eq "POST" and starts_with(http.request.uri.path, "/admin/v1/media")` → Rate limit 20 / 15 min / IP → Block 429. Origin additionally enforces 20/admin/hour.
7. Admin hostname mTLS is configured in `cloudflare-mtls-ca.md`; media cache rules in `cloudflare-cache.md`.

Deploy / commands
```bash
cd /opt/bethanys-bakery
sh infra/scripts/deploy.sh prod
sh infra/scripts/logs.sh prod cloudflared
docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/prod/.env \
  ps --format '{{.Names}} {{.Ports}}' | grep -E '0\.0\.0\.0|::' || echo 'no published ports'
```

Verification
```bash
curl -fsS https://bakery.cherry-home.com/api/v1/config
curl -sS -o /dev/null -w '%{http_code}\n' -H 'CF-IPCountry: CA' \
  https://bakery.cherry-home.com/api/v1/config                       # 403 COUNTRY_BLOCKED
curl -sS -o /dev/null -w '%{http_code}\n' \
  https://bakery.cherry-home.com/api/v1/config                       # 403 when CF-IPCountry missing
curl -sS -o /dev/null -w '%{http_code}\n' -H 'CF-IPCountry: US' -H 'Origin: https://evil.example' \
  https://bakery.cherry-home.com/api/v1/auth/csrf                    # 403, no CORS
```
External-network origin probe (run from cellular/home ISP, NEVER from inside the tunnel network)
```bash
ORIGIN_IP=<host-public-ip>
for p in 80 443 8080 5432; do
  timeout 3 nc -zv "$ORIGIN_IP" "$p" 2>&1 || echo "$p closed/filtered"
done
```
Expected: every direct port is refused/filtered; only the Cloudflare hostnames serve traffic. Cloudflare WAF geo block must not affect provider webhooks (`/api/v1/webhooks/**`).

Rollback
- Revert the rule in Cloudflare (save the previous rule expression first) or disable the rule; tunnel token rollback = put the previous token in `.env` and redeploy. Do not publish host ports as a workaround.

Notify
- Owner + ops on-call before tunnel/token changes; security owner for WAF/geo changes.
