# Mailgun setup (email OTP + admin alerts)

Prerequisites
- Mailgun account; domain `mail.cherry-home.com` (or chosen subdomain) verified with DNS access.
- `/opt/bethanys-bakery/.env` is mode 600; API key stored in the owner's secret manager.
- `ADMIN_ALERT_EMAIL` set to the ops distribution address. It receives the
  mandatory `ORDER_PAID` email and every new-order admin email. Full addresses
  are masked in API responses and logs.

Console / DNS
1. Mailgun → Domains → Add Domain `mail.cherry-home.com`.
2. Publish the DKIM TXT records, SPF include, and DMARC TXT record Mailgun shows. Wait for "Verified".
3. Mailgun → API Keys → create a key with Sending access; copy once.
4. Use `MAILGUN_API_BASE=https://api.mailgun.net/v3` (EU region: `https://api.eu.mailgun.net/v3`).

Set env (single LXC)
```bash
cd /opt/bethanys-bakery
ENV_FILE=/opt/bethanys-bakery/.env
read -rsp 'Mailgun API key: ' MAILGUN_API_KEY; echo
KEY_ESC=$(printf '%s' "$MAILGUN_API_KEY" | sed 's/[&|\\]/\\&/g')
sed -i "s|^MAILGUN_API_KEY=.*|MAILGUN_API_KEY=$KEY_ESC|" "$ENV_FILE"
unset MAILGUN_API_KEY
# Edit with a mode-600 editor: MAILGUN_DOMAIN, MAILGUN_FROM, ADMIN_ALERT_EMAIL.
# Example: MAILGUN_DOMAIN=mail.cherry-home.com
#          MAILGUN_FROM=Bethany's Bakery <no-reply@mail.cherry-home.com>
git pull --ff-only
docker compose -f infra/compose.prod.yml --env-file "$ENV_FILE" up -d --build
sh infra/scripts/status.sh
```

Verification (run from a US network; geo rule blocks non-US customer API)
```bash
curl -sS -X POST https://bakery.cherry-home.com/api/v1/auth/otp/request \
  -H 'Content-Type: application/json' \
  --data '{"channel":"EMAIL","destination":"owner@cherry-home.com"}'   # 202, masked destination
LOG_LINES=100 sh /opt/bethanys-bakery/infra/scripts/logs.sh api | grep -i 'mailgun\|notification' | tail -n 20
```
Check Mailgun → Logs for `202 Accepted`, then verify the code in the app.
Trigger a new order and confirm an admin email is queued for `ADMIN_ALERT_EMAIL`.

Rollback
- Restore previous env values and redeploy with the raw Compose command above.
  Mailgun DNS records can stay; no code change. A bad key yields
  `503 NOT_CONFIGURED` or a provider error in logs.

Notify
- Owner + ops on-call after DNS verification and after first successful email; support if delivery is delayed.
