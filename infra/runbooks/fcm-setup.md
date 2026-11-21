# Firebase Cloud Messaging (FCM) setup

Prerequisites
- Firebase project with Cloud Messaging enabled; Android app package registered.
- Service account JSON with `firebasemessaging` scope; owner-approved storage.

Console
1. Firebase console → Project settings → Service accounts → Generate new private key (JSON). Store offline; never commit.
2. Note `FCM_PROJECT_ID` from Project settings.
3. Android app registers its FCM token via `PUT /admin/v1/devices/{deviceId}/push-token`.

Set env
```bash
cd /opt/bethanys-bakery
ENV=prod
ENV_FILE=/opt/bethanys-bakery/$ENV/.env
jq -c . /secure/path/firebase-service-account.json > /tmp/fcm.json
FCM_JSON=$(cat /tmp/fcm.json)
ESC=$(printf '%s' "$FCM_JSON" | sed 's/[&|\\]/\\&/g')
sed -i "s|^FCM_SERVICE_ACCOUNT_JSON=.*|FCM_SERVICE_ACCOUNT_JSON=$ESC|" "$ENV_FILE"
sed -i "s|^FCM_PROJECT_ID=.*|FCM_PROJECT_ID=<project-id>|" "$ENV_FILE"
chmod 600 "$ENV_FILE"
rm -f /tmp/fcm.json
sh infra/scripts/check-env.sh "$ENV"
sh infra/scripts/deploy.sh "$ENV"
```

Verification
```bash
# Register a token from the paired device (requires mTLS + device key + admin bearer).
DEVICE_ID=<uuid>
FCM_TOKEN=<device-token>
curl -fsS -X PUT \
  "https://bakery-admin-api.cherry-home.com/admin/v1/devices/$DEVICE_ID/push-token" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "X-Device-Key: $DEVICE_KEY" \
  --cert /path/to/device.crt --key /path/to/device.key \
  --data "$(jq -nc --arg t "$FCM_TOKEN" '{token:$t,appVersion:"1.0.0"}")"   # 204

curl -fsS https://bakery-admin-api.cherry-home.com/admin/v1/devices \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "X-Device-Key: $DEVICE_KEY" \
  --cert /path/to/device.crt --key /path/to/device.key \
  | jq -r '.[] | select(.id=="'"$DEVICE_ID"'") | .fcmRegistered'            # true
sh /opt/bethanys-bakery/infra/scripts/logs.sh prod api | grep -i 'fcm\|push' | tail -n 20
```
Trigger an order status notification from staging and confirm the paired device receives it. Missing FCM config must not break email/SMS delivery.

Rollback
- Restore previous env values and redeploy. Setting the push token to `null` clears registration:
  `curl -X PUT ... --data '{"token":null}'`. No DB migration.

Notify
- Owner + Android on-call after first push is received; security owner if service account key is exposed (rotate in Firebase immediately).
