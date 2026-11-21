# Revoke a paired admin device

Prerequisites
- Valid admin session (password + TOTP/recovery code) from the current paired
  device. Exactly one device is active at a time.
- Device UUID from `GET /admin/v1/devices`; admin API mTLS cert/key and
  `X-Device-Key` of the revoking device.

Revoke at origin (immediate)
```bash
DEVICE_ID=<uuid-to-revoke>
ADMIN_TOKEN=<access-token>
DEVICE_KEY=<revoking-device-key>
curl -fsS -X DELETE \
  "https://bakery.cherry-home.com/admin/v1/devices/$DEVICE_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "X-Device-Key: $DEVICE_KEY" \
  --cert /path/to/device.crt --key /path/to/device.key
```
App-level status becomes `REVOKED` immediately: the API key, cert fingerprint,
and refresh-token family stop authenticating. After revocation, pair the
replacement device (`device-pairing.md`).

Edge revocation (CA-wide)
- `generate-ca.sh` does not maintain an OpenSSL serial index, so per-device CRL
  entries are unavailable by default; rotate the CA to invalidate all old certs
  at the edge (`cloudflare-mtls-ca.md`). App-level revocation above is immediate.

Verification
```bash
# Using the revoked device's cert/key/key: expect 401.
curl -sS -o /dev/null -w '%{http_code}\n' \
  https://bakery.cherry-home.com/admin/v1/app-info \
  -H "X-Device-Key: $REVOKED_DEVICE_KEY" \
  --cert /path/to/revoked.crt --key /path/to/revoked.key      # 401
curl -fsS https://bakery.cherry-home.com/admin/v1/devices \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "X-Device-Key: $DEVICE_KEY" \
  --cert /path/to/device.crt --key /path/to/device.key | jq -r '.[] | select(.id=="'"$DEVICE_ID"'") | .status'
```
Expected: `REVOKED`/absent from active list; admin audit shows device revocation.

Rollback
- Re-pair a new device (`device-pairing.md`). There is no supported "unrevoke":
  status is terminal by design.

Notify
- Owner + security owner immediately for lost/stolen devices; ops on-call for CA rotation.
