# Mailgun setup (email OTP + admin alerts)

Prerequisites
- Mailgun account; domain `mail.cherry-home.com` (or chosen subdomain) verified with DNS access.
- Env files mode 600; API key stored in the owner's secret manager.
- `ADMIN_ALERT_EMAIL` set to the ops distribution address.

Console / DNS
1. Mailgun → Domains → Add Domain `mail.cherry-home.com`.
2. Publish the DKIM TXT records, SPF include, and DMARC TXT record Mailgun shows. Wait for "Verified".
3. Mailgun → API Keys → create a key with Sending access; copy once.
4. Use `MAILGUN_API_BASE=https://api.mailgun.net/v3` (EU region: `https://api.eu.mailgun.net/v3`).

Set env (per environment)
```bash
cd /opt/bethanys-bakery
ENV=prod
ENV_FILE=/opt/bethanys-bakery/$ENV/.env
read -rsp 'Mailgun API key: ' MAILGUN_API_KEY; echo
KEY_ESC=$(printf '%s' "$MAILGUN_API_KEY" | sed 's/[&|\\]/\\&/g')
sed -i "s|^MAILGUN_API_KEY=.*|MAILGUN_API_KEY=$KEY_ESC|" "$ENV_FILE"
unset MAILGUN_API_KEY
# Edit with a mode-600 editor: MAILGUN_DOMAIN, MAILGUN_FROM, ADMIN_ALERT_EMAIL.
# Example: MAILGUN_DOMAIN=mail.cherry-home.com
#          MAILGUN_FROM=Bethany's Bakery <no-reply@mail.cherry-home.com>
sh infra/scripts/check-env.sh "$ENV"
sh infra/scripts/deploy.sh "$ENV"
```

Verification (run from a US network; geo rule blocks non-US customer API)
```bash
curl -sS -X POST https://bakery.cherry-home.com/api/v1/auth/otp/request \
  -H 'Content-Type: application/json' \
  --data '{"channel":"EMAIL","destination":"owner@cherry-home.com"}'   # 202, masked destination
sh /opt/bethanys-bakery/infra/scripts/logs.sh prod api | grep -i 'mailgun\|notification' | tail -n 20
```
Check Mailgun → Logs for `202 Accepted`, then verify the code in the app. Confirm admin alert email by triggering a failed admin login and checking `ADMIN_ALERT_EMAIL`.

Rollback
- Restore previous env values and redeploy (`rollback.md`). Mailgun DNS records can stay; no code change. A bad key yields `503 NOT_CONFIGURED` or provider error in logs.

Notify
- Owner + ops on-call after DNS verification and after first successful email; support if delivery is delayed.
