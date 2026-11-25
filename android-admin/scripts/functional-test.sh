#!/usr/bin/env bash
set -euo pipefail

# S9 dummy functional test for A1. Loops back to the host from an emulator via 10.0.2.2.
# No registry, no DB access, no real pairing, no provider credentials.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID_DIR="$ROOT/android-admin"
SERVER_DIR="$ROOT/server"
IMAGE="bethanys-bakery-dummy:a1"
CONTAINER="bb-dummy-a1-$$"
API_PORT="${API_PORT:-8080}"
MGMT_PORT="${MGMT_PORT:-9090}"
API_BASE="http://127.0.0.1:${API_PORT}"
MGMT_BASE="http://127.0.0.1:${MGMT_PORT}"
COOKIES="$(mktemp)"
ADMIN_EMAIL="admin@dummy.local"
ADMIN_PASSWORD='DummyAdmin!234'
TOTP_SECRET="JBSWY3DPEHPK3PXP"
DEVICE_KEY="dummy-device-key"
CUSTOMER_EMAIL="customer@dummy.local"
CUSTOMER_OTP="123456"

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  rm -f "$COOKIES"
}
trap cleanup EXIT

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }
}
require docker
require curl
require node
if [ "${API_ONLY:-0}" != "1" ]; then
  require adb
fi

totp() {
  node -e '
const crypto = require("crypto");
const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
const secret = process.argv[1].toUpperCase().replace(/=+$/, "");
let bits = "";
for (const char of secret) bits += alphabet.indexOf(char).toString(2).padStart(5, "0");
const key = Buffer.alloc(Math.floor(bits.length / 8));
for (let i = 0; i < key.length; i += 1) key[i] = parseInt(bits.slice(i * 8, i * 8 + 8), 2);
const counter = Math.floor(Date.now() / 1000 / 30);
const buffer = Buffer.alloc(8);
buffer.writeBigUInt64BE(BigInt(counter));
const mac = crypto.createHmac("sha1", key).update(buffer).digest();
const offset = mac[mac.length - 1] & 0x0F;
const value = (mac.readUInt32BE(offset) & 0x7FFFFFFF) % 1000000;
console.log(String(value).padStart(6, "0"));
' "$TOTP_SECRET"
}

field() {
  node -e '
const data = JSON.parse(require("fs").readFileSync(0, "utf8"));
let value = data;
for (const key of process.argv[1].replace(/^\./, "").split(".")) value = value == null ? undefined : value[key];
if (value == null) process.exit(1);
console.log(value);
' "$1" <<<"$2"
}

json_string() {
  node -e 'console.log(JSON.stringify(process.argv[1]))' "$1"
}

header_value() {
  case "$1" in
    *$'\r'*|*$'\n'*) echo "Unsafe header value from dummy API" >&2; exit 1 ;;
  esac
  printf '%s' "$1"
}

echo "Building S9 dummy image from $SERVER_DIR"
(cd "$SERVER_DIR" && docker build --target dummy -t "$IMAGE" .)

echo "Starting loopback-only dummy container"
docker run -d --rm --name "$CONTAINER" \
  -p "127.0.0.1:${API_PORT}:8080" \
  -p "127.0.0.1:${MGMT_PORT}:9090" \
  "$IMAGE" >/dev/null

echo "Waiting for readiness"
ready=0
for _ in $(seq 1 60); do
  if curl -fsS "$MGMT_BASE/actuator/health/readiness" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
[ "$ready" -eq 1 ] || { echo "Dummy image did not become ready" >&2; exit 1; }

config="$(curl -fsS "$API_BASE/api/v1/config")"
[ "$(field .bakeryName "$config")" = "Dummy Bakery" ] || { echo "Unexpected dummy bakery name" >&2; exit 1; }

echo "Seeding customer session and preferences through the real API"
: >"$COOKIES"
csrf="$(field .token "$(curl -fsS -c "$COOKIES" -b "$COOKIES" "$API_BASE/api/v1/auth/csrf")")"
challenge="$(field .challengeId "$(curl -fsS -c "$COOKIES" -b "$COOKIES" \
  -X POST "$API_BASE/api/v1/auth/otp/request" \
  -H "X-CSRF-Token: $(header_value "$csrf")" \
  -H 'Content-Type: application/json' \
  -d "{\"channel\":\"EMAIL\",\"destination\":$(json_string "$CUSTOMER_EMAIL")}")")"
curl -fsS -c "$COOKIES" -b "$COOKIES" \
  -X POST "$API_BASE/api/v1/auth/otp/verify" \
  -H 'Content-Type: application/json' \
  -d "{\"challengeId\":$(json_string "$challenge"),\"code\":$(json_string "$CUSTOMER_OTP")}" >/dev/null

csrf="$(field .token "$(curl -fsS -c "$COOKIES" -b "$COOKIES" "$API_BASE/api/v1/auth/csrf")")"
curl -fsS -c "$COOKIES" -b "$COOKIES" \
  -X PATCH "$API_BASE/api/v1/me" \
  -H "X-CSRF-Token: $(header_value "$csrf")" \
  -H 'Content-Type: application/json' \
  -d '{"name":"A1 Functional Customer"}' >/dev/null
curl -fsS -c "$COOKIES" -b "$COOKIES" \
  -X PUT "$API_BASE/api/v1/me/notification-preferences" \
  -H "X-CSRF-Token: $(header_value "$csrf")" \
  -H 'Content-Type: application/json' \
  -d '{"smsConsent":true,"preferences":[
    {"eventType":"MENU_PUBLISHED","emailEnabled":true,"smsEnabled":false},
    {"eventType":"ORDER_REMINDER","emailEnabled":true,"smsEnabled":false},
    {"eventType":"ORDER_ACCEPTED","emailEnabled":true,"smsEnabled":false},
    {"eventType":"ORDER_READY","emailEnabled":true,"smsEnabled":false},
    {"eventType":"ORDER_DELAYED","emailEnabled":true,"smsEnabled":false},
    {"eventType":"ORDER_REJECTED","emailEnabled":true,"smsEnabled":false},
    {"eventType":"ORDER_CANCELLED","emailEnabled":true,"smsEnabled":false},
    {"eventType":"ORDER_EXPIRED","emailEnabled":true,"smsEnabled":false},
    {"eventType":"MENU_CANCELLED","emailEnabled":true,"smsEnabled":false}
  ]}' >/dev/null

echo "Seeding menu, pickup window, offering, and order through the real API"
login="$(curl -fsS -X POST "$API_BASE/admin/v1/auth/login" \
  -H "X-Device-Key: $DEVICE_KEY" \
  -H 'CF-Connecting-IP: 127.0.0.1' \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\",\"totpCode\":\"$(totp)\"}")"
admin_token="$(field .accessToken "$login")"
admin_headers=(-H "Authorization: Bearer $(header_value "$admin_token")" -H "X-Device-Key: $(header_value "$DEVICE_KEY")")

bake_date="$(TZ=America/Denver date -d '+20 days' +%F)"
start_at="$(TZ=America/Denver date -d "$bake_date 09:00" -Iseconds)"
end_at="$(TZ=America/Denver date -d "$bake_date 11:00" -Iseconds)"

template_id="$(field .id "$(curl -fsS -X POST "$API_BASE/admin/v1/item-templates" \
  "${admin_headers[@]}" -H 'Content-Type: application/json' \
  -d '{"name":"Cinnamon Roll","active":true,"allergenTags":["gluten"],"customizationGroups":[]}')")"
menu_id="$(field .id "$(curl -fsS -X POST "$API_BASE/admin/v1/menus" \
  "${admin_headers[@]}" -H 'Content-Type: application/json' \
  -d '{"title":"A1 Functional Test Menu"}')")"
date_id="$(field .id "$(curl -fsS -X POST "$API_BASE/admin/v1/menus/$menu_id/dates" \
  "${admin_headers[@]}" -H 'Content-Type: application/json' \
  -d "{\"bakeDate\":$(json_string "$bake_date")}")")"
window_id="$(field .id "$(curl -fsS -X POST "$API_BASE/admin/v1/menu-dates/$date_id/pickup-windows" \
  "${admin_headers[@]}" -H 'Content-Type: application/json' \
  -d "{\"label\":\"Morning\",\"startAt\":$(json_string "$start_at"),\"endAt\":$(json_string "$end_at"),\"maxOrders\":10}")")"
offering_id="$(field .id "$(curl -fsS -X POST "$API_BASE/admin/v1/menu-dates/$date_id/offerings" \
  "${admin_headers[@]}" -H 'Content-Type: application/json' \
  -d "{\"itemTemplateId\":$(json_string "$template_id"),\"priceCents\":650,\"quantityLimit\":20,\"windowIds\":[$(json_string "$window_id")]}")")"
curl -fsS -X POST "$API_BASE/admin/v1/menus/$menu_id/publish" \
  "${admin_headers[@]}" -H 'Content-Type: application/json' \
  -d '{"notificationEnabled":true}' >/dev/null

csrf="$(field .token "$(curl -fsS -c "$COOKIES" -b "$COOKIES" "$API_BASE/api/v1/auth/csrf")")"
order_response="$(curl -sS -w $'\n%{http_code}' -c "$COOKIES" -b "$COOKIES" \
  -X POST "$API_BASE/api/v1/orders" \
  -H "X-CSRF-Token: $(header_value "$csrf")" -H 'Content-Type: application/json' \
  -d "{\"menuDateId\":$(json_string "$date_id"),\"pickupWindowId\":$(json_string "$window_id"),\"idempotency_key\":\"a1-functional-test-1\",\"items\":[{\"offeringId\":$(json_string "$offering_id"),\"quantity\":2}]}")"
order_status="${order_response##*$'\n'}"
order_body="${order_response%$'\n'*}"
case "$order_status" in
  200|201) ;;
  *) echo "Order creation failed ($order_status): $order_body" >&2; exit 1 ;;
esac
order_id="$(field .id "$order_body")"
curl -fsS "$API_BASE/admin/v1/orders/$order_id" "${admin_headers[@]}" >/dev/null
preview="$(curl -fsS -X POST "$API_BASE/admin/v1/notifications/recipient-preview?page=0&size=20" \
  "${admin_headers[@]}" -H 'Content-Type: application/json' \
  -d "{\"eventType\":\"MENU_PUBLISHED\",\"entityType\":\"MENU\",\"entityId\":$(json_string "$menu_id")}")"
field .items "$preview" >/dev/null

if [ "${API_ONLY:-0}" = "1" ]; then
  echo "API-only mode: S9 seed verified; skipping Android install/instrumentation (no adb required)"
  exit 0
fi

echo "Building debug APKs with dummy device key (debug-only)"
android_api_url="http://10.0.2.2:${API_PORT}/admin/v1/"
(cd "$ANDROID_DIR" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon)

echo "Installing on connected Android device/emulator"
adb get-state >/dev/null
adb install -r -t "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb install -r -t "$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null

echo "Running app-side authenticated round-trip"
adb shell am instrument -w \
  -e class com.bethanysbakery.admin.core.network.S9DummyFunctionalTest \
  -e apiUrl "$android_api_url" \
  -e deviceKey "$DEVICE_KEY" \
  -e adminToken "$admin_token" \
  com.bethanysbakery.admin.test/androidx.test.runner.AndroidJUnitRunner

echo "A1 S9 functional test passed"
