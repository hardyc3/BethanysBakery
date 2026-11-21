# Pair an admin device

Prerequisites
- Admin device CA uploaded to Cloudflare (`cloudflare-mtls-ca.md`).
- At least one active admin exists (`bootstrap-admin.sh`); `postgres` is running.
- Pair hostname `bakery-pair.cherry-home.com` has no mTLS but is rate-limited 10/IP/15m.
- Device has secure storage for the API key and private key; app version meets `ADMIN_APP_MIN_VERSION`.

Issue pairing code
```bash
cd /opt/bethanys-bakery
sh infra/scripts/pair-device.sh prod          # staging: replace prod with staging
```
Output contains a one-time code (15-minute TTL), pair URL, admin API URL, and QR. The code hash is stored; plaintext is shown once.

On the Android device
- Scan the QR (or enter the pair URL + code). Confirm the admin API hostname matches `APP_ADMIN_API_URL`.
- App sends name, public key, and EC P-256 CSR to `POST /admin/v1/devices/pair`; server returns cert (180 days), CA cert, and 256-bit API key once.
- Store key material in Android Keystore; never export or log it.

Verification
```bash
DEVICE_KEY=<from-app>
curl -fsS https://bakery-admin-api.cherry-home.com/admin/v1/app-info \
  -H "X-Device-Key: $DEVICE_KEY" \
  --cert /path/to/device.crt --key /path/to/device.key
sh /opt/bethanys-bakery/infra/scripts/logs.sh prod api | grep -i 'DEVICE_PAIRED' | tail -n 5
```
- Reusing the same pairing code must fail (single-use).
- Without either the device cert or `X-Device-Key`, the request returns 401.
- `GET /admin/v1/devices` lists the new device as `ACTIVE`.

Rollback
- If pairing leaks the code or key, revoke the device immediately (`device-revocation.md`) and issue a new code. A failed pairing leaves no active device; retry with a fresh code.

Notify
- Owner + ops on-call after each production pairing; security owner if a code/key is exposed.
