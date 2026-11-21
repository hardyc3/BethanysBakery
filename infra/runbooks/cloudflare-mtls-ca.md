# Cloudflare mTLS CA, certificate forwarding, and revocation

The mTLS rule is a **path rule on the one public hostname**, not a hostname-wide
setting. It protects `/admin/v1/**` except `/admin/v1/devices/pair`. Cloudflare
handles HTTPS certificates; there is no origin cert and no edge cert pinning
rotation runbook.

Prerequisites
- `infra/scripts/generate-ca.sh` already created
  `/opt/bethanys-bakery/secrets/device-ca.{key,crt}` (mode 600).
- The API env points to `/run/secrets/device-ca.{crt,key}`.
- One hostname is routed through the tunnel: `/admin/v1` → `api:8080`.

Generate / inspect CA
```bash
sh /opt/bethanys-bakery/infra/scripts/generate-ca.sh /opt/bethanys-bakery/secrets
openssl x509 -in /opt/bethanys-bakery/secrets/device-ca.crt -noout -fingerprint -sha256 -subject -dates
stat -c '%a %n' /opt/bethanys-bakery/secrets/device-ca.key   # 600
```

Cloudflare console
1. SSL/TLS → Client Certificates → Create/Upload Certificate Authority; upload
   `device-ca.crt` for `bakery.cherry-home.com`. Do **not** upload `device-ca.key`.
2. Enable Client Certificate Forwarding for that hostname so admin requests
   carry `Cf-Authenticated-Client-Cert` and
   `Cf-Authenticated-Client-Cert-Verified`.
3. Security → WAF → Custom rules → add:
   `(starts_with(http.request.uri.path, "/admin/v1/") and http.request.uri.path ne "/admin/v1/devices/pair" and cf.client.cert.verified ne "true")` → **Block** (403).
   Include the exact `/admin/v1` path in the rule if your WAF does not match it
   via the prefix. The origin rejects any admin request unless the forwarded
   cert is verified and its fingerprint matches an `ACTIVE` device.
4. `/admin/v1/devices/pair` stays mTLS-exempt and is rate limited at the edge
   (10/IP/15 min, see `cloudflare-tunnel.md`). Exactly one admin device may be
   `ACTIVE`; a second pairing returns 409 `INVALID_STATE`.
5. `/api/v1/**` and `/` have no mTLS rule. `/api/v1/webhooks/textbee` is
   unauthenticated at the edge and signature-verified at the origin.

Verification
```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  https://bakery.cherry-home.com/admin/v1/app-info                  # 403 at edge without cert
curl -sS -o /dev/null -w '%{http_code}\n' \
  https://bakery.cherry-home.com/admin/v1/devices/pair             # reaches origin (rate limited)
curl -fsS https://bakery.cherry-home.com/admin/v1/app-info \
  --cert /path/to/device.crt --key /path/to/device.key \
  -H "X-Device-Key: $DEVICE_KEY"                                   # 200 with valid paired device
sh /opt/bethanys-bakery/infra/scripts/logs.sh api | grep -i 'mtls\|certificate' || true
```

Revocation
- App-level: revoke the device first via `device-revocation.md`; it returns 401
  immediately at origin. With one active device, pair a replacement after revoking.
- CRL: `generate-ca.sh` creates a bare CA with no OpenSSL index, so per-device
  CRL entries are unavailable by default. If serial tracking is added later,
  publish a PEM CRL over HTTPS and attach it to the CA in Cloudflare. Otherwise
  the safe edge revocation is CA rotation below.

CA rotation
```bash
# On the LXC, after a Proxmox snapshot: move old files aside, then regenerate.
mv /opt/bethanys-bakery/secrets/device-ca.key /opt/bethanys-bakery/secrets/device-ca.key.old
mv /opt/bethanys-bakery/secrets/device-ca.crt /opt/bethanys-bakery/secrets/device-ca.crt.old
sh /opt/bethanys-bakery/infra/scripts/generate-ca.sh /opt/bethanys-bakery/secrets
cd /opt/bethanys-bakery
git pull --ff-only
docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/.env up -d --build
```
Upload the new CA in Cloudflare, re-pair the device (`device-pairing.md`), then
move `.old` files into the owner's offline secret store.

Verification
- Old CA cert is removed from Cloudflare; old device certs fail; newly paired
  cert succeeds; admin login works.
- No CA private key leaves `/opt/bethanys-bakery/secrets` and none is in git.

Rollback
- Re-upload the previous CA in Cloudflare, restore `.old` files, redeploy with
  the raw Compose command, and re-pair if needed. Never leave two CAs enabled
  longer than the change window.

Notify
- Owner + security owner for CA upload/rotation; ops on-call for re-pair window.
