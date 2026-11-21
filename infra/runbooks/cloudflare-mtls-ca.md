# Cloudflare mTLS CA, certificate forwarding, and revocation

Prerequisites
- `infra/scripts/generate-ca.sh` already created `/opt/bethanys-bakery/secrets/device-ca.{key,crt}` (mode 600).
- Admin API hostname is routed through the tunnel; app env points to `/run/secrets/device-ca.{crt,key}`.

Generate / inspect CA
```bash
sh /opt/bethanys-bakery/infra/scripts/generate-ca.sh /opt/bethanys-bakery/secrets
openssl x509 -in /opt/bethanys-bakery/secrets/device-ca.crt -noout -fingerprint -sha256 -subject -dates
stat -c '%a %n' /opt/bethanys-bakery/secrets/device-ca.key   # 600
```

Cloudflare console
1. SSL/TLS → Client Certificates → Create/Upload Certificate Authority, upload `device-ca.crt` for `bakery-admin-api.cherry-home.com` (and staging admin hostname). Do **not** upload `device-ca.key`.
2. Enable Client Certificate Forwarding for that hostname so requests carry `Cf-Authenticated-Client-Cert` and `Cf-Authenticated-Client-Cert-Verified`.
3. Add WAF rule:
   `(http.host eq "bakery-admin-api.cherry-home.com" and not starts_with(http.request.uri.path, "/admin/v1/devices/pair") and (cf.client.cert.verified ne "true"))` → **Block** (403).
   The origin also rejects requests unless `Cf-Authenticated-Client-Cert-Verified: true` and the forwarded cert fingerprint matches an `ACTIVE` device.
4. Pair hostname has no mTLS; it only gets the strict 10/IP/15m rule from `cloudflare-tunnel.md`.

Verification
```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  https://bakery-admin-api.cherry-home.com/admin/v1/app-info          # 401 without cert/key/device key
curl -fsS https://bakery-admin-api.cherry-home.com/admin/v1/app-info \
  --cert /path/to/device.crt --key /path/to/device.key \
  -H "X-Device-Key: $DEVICE_KEY"                                      # 200 with valid paired device
sh /opt/bethanys-bakery/infra/scripts/logs.sh prod api | grep -i 'mtls\|certificate' || true
```

Revocation (CRL / edge)
- App-level: revoke the device first via `device-revocation.md`; it returns 401 immediately at origin.
- CRL: Cloudflare's CA record supports a CRL only when issued certificates have tracked serial numbers. `generate-ca.sh` creates a bare CA with no OpenSSL index, so per-device CRL entries are not available by default. If your CA setup tracks serials (or you migrate to one), publish a PEM CRL over HTTPS and attach it to the CA in SSL/TLS → Client Certificates → Edit CA → CRL URL; then verify the revoked cert returns 401/403 at edge.
- If no serial-tracked CRL exists, the safe edge revocation is CA rotation: generate a new CA, upload it, re-pair every device, then remove the old CA. This invalidates all old certs at once.

CA rotation
```bash
# On new CA (after backup), move old files aside, then regenerate.
mv /opt/bethanys-bakery/secrets/device-ca.key /opt/bethanys-bakery/secrets/device-ca.key.old
mv /opt/bethanys-bakery/secrets/device-ca.crt /opt/bethanys-bakery/secrets/device-ca.crt.old
sh /opt/bethanys-bakery/infra/scripts/generate-ca.sh /opt/bethanys-bakery/secrets
sh /opt/bethanys-bakery/infra/scripts/deploy.sh prod
```
Upload the new CA in Cloudflare, re-pair devices (`device-pairing.md`), then delete `.old` files into offline secret storage.

Verification
- Old CA cert is removed from Cloudflare; old device certs fail; newly paired cert succeeds; admin login works.
- No CA private key leaves `/opt/bethanys-bakery/secrets` and none is in git.

Rollback
- Re-upload the previous CA in Cloudflare, restore `.old` files, redeploy, and re-pair if needed. Never leave two CAs enabled longer than the change window.

Notify
- Owner + security owner for CA upload/rotation; ops on-call for re-pair window.
