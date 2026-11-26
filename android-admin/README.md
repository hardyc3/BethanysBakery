# Bethany's Bakery Admin (Android)

A1 scaffold: Compose/Material 3 admin app with secure device pairing, Keystore-backed EC identity, mTLS, certificate pinning, and authenticated networking. Login and admin screens arrive in A2.

Package: `com.bethanysbakery.admin`  
Build types: `debug`, `release`  
Min SDK: 26 · Compile/target SDK: 37.2

## Layout

```text
app/src/main/java/com/bethanysbakery/admin/
  core/config      # build-time URL, pin, and debug policy
  core/network     # OkHttp/Retrofit, pinning, TLS, mTLS key manager, error mapping
  core/security    # Keystore EC key, CSR, AES-GCM secret files, expiry checks
  core/storage     # DataStore metadata + encrypted API key/token files
  core/ui          # theme, large-target status/loading/error components
  core/error       # user-facing error messages
  feature/pairing   # QR/manual entry, repository, ViewModel, screens
  feature/login    # A1 post-pairing placeholder
  navigation
```

## Requirements

- JDK 17+
- Android SDK platform `android-37.2` and build tools `36.0.0`
- `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or a gitignored `local.properties` with `sdk.dir=...`
- Android device/emulator API 26+ for instrumented and functional tests
- Docker and Node.js for the S9 dummy functional test (TOTP and JSON assertions)

## Build and test

```bash
cd android-admin
./gradlew test                         # JVM unit tests
./gradlew lintDebug                    # Android lint
./gradlew assembleDebug                # debug APK
./gradlew connectedDebugAndroidTest    # Keystore/DataStore tests on device
```

Release builds require pins:

```bash
ADMIN_API_PINS='sha256/<primary-base64>,sha256/<backup-base64>' \
  ./gradlew assembleRelease
```

Fewer than two pins fails the build. A8 supplies final production pins and signing.

## Configuration

Defaults live in `gradle.properties`; all values are build-time and injected into `BuildConfig`:

| Property | Purpose |
|---|---|
| `APP_PAIR_URL` | Exact QR pairing endpoint. Default `https://bakery.cherry-home.com/admin/v1/devices/pair`. |
| `APP_ADMIN_API_URL` | Exact admin API base. Default `https://bakery.cherry-home.com/admin/v1`. |
| `ADMIN_API_PINS` | Comma-separated SPKI SHA-256 pins. Empty locally; release needs primary + backup. |
| `DEBUG_LAN_HOSTS` | Debug-only comma-separated hosts allowed for `http://host:<port>/...`. |
| `ADMIN_API_PINNING_DEBUG` | Debug-only pin enforcement switch (`false` by default). |
| `ADMIN_TEST_CA_PEM` | Debug-only PEM CA for local mTLS servers. Never set for release. |

`10.0.2.2` is always allowed for `http` in debug. Release rejects `http`, userinfo, query strings, fragments, non-default ports, and any host/path other than the two exact configured URLs. QR payloads must use this shape:

```json
{"v":1,"pairUrl":"https://bakery.cherry-home.com/admin/v1/devices/pair","apiUrl":"https://bakery.cherry-home.com/admin/v1","code":"..."}
```

Unknown `v`, malformed JSON, wrong hosts/paths, and blank codes are rejected before any network call.

## Pairing and credentials

- EC P-256 key generated in Android Keystore alias `device_key`; private key is non-exportable.
- CSR built with BouncyCastle from that key; POST includes `pairingCode`, `deviceName`, `publicKeyPem`, `csrPem`, `appVersion`.
- Response stores `deviceId`, API URL, and cert expiry in DataStore; API key and token files are encrypted with a Keystore AES-GCM key.
- Client certificate chain is stored only in an encrypted file.
- Post-pairing requests use `X-Device-Key`, `X-App-Version`, `Accept: application/json`, and `X-Request-Id`; no header/body values are logged.
- Cert expiry within 14 days shows a re-pair warning; expired cert returns to pairing.
- Unpair clears DataStore, encrypted files, Keystore key entry, and in-memory API key.

## Pinning handoff

Never hardcode a single pin. `ADMIN_API_PINS` is build-time config; release variants fail configuration with fewer than two valid `sha256/<base64>` pins. `CertificatePinner` enforces both pins for the exact API host when `PINNING_ENABLED=true`. A8/Cloudflare owns final pin values, signing, and deployment rotation; A1 does not publish a rotation runbook.

## Local S9 dummy functional test

The dummy image is built locally from `server/Dockerfile --target dummy`; no registry. It binds only `127.0.0.1`, uses fixed secrets, and must never be exposed to LAN/prod/staging. The test seeds menus/orders/preferences through the real API only; no direct DB access, no real pairing, no provider credentials.

```bash
android-admin/scripts/functional-test.sh

# API-only seed verification when no emulator/adb is attached
API_ONLY=1 android-admin/scripts/functional-test.sh
```

The script:

1. Builds and starts the dummy image on loopback ports `8080` (API) and `9090` (health/scrape).
2. Verifies `GET /api/v1/config` returns `Dummy Bakery`.
3. Authenticates a customer with OTP `123456`, sets all nine notification preferences via `PUT /api/v1/me/notification-preferences` with `GET /api/v1/auth/csrf` + `X-CSRF-Token`.
4. Logs in as `admin@dummy.local` with `X-Device-Key: dummy-device-key` and current TOTP from `JBSWY3DPEHPK3PXP`.
5. Seeds item template, menu, bake date, pickup window, offering, publishes the menu, creates an order, and checks admin order + recipient preview endpoints.
6. Builds debug APKs, installs them on the connected device/emulator, and passes the fixed device key, API URL, and fresh admin token as instrumentation arguments. `S9DummyFunctionalTest` then makes an app-side `GET /admin/v1/devices` round-trip with the paired-client headers.

### Docker integration tests

`S9DummyServerIntegrationTest` starts the locally built dummy container with Testcontainers and covers happy paths without `adb`: readiness/config, customer OTP auth + profile/preferences, admin catalog/menu/order/preview seeding, and the A1 paired client's `GET /admin/v1/devices` round-trip. It is tagged `docker-integration`, so normal `./gradlew test` excludes it.

```bash
cd android-admin
./gradlew testDebugUnitTest -PincludeDockerIntegration=true \
  --tests '*S9DummyServerIntegrationTest' --no-daemon
```

Requires Docker. The image is built from `server/Dockerfile --target dummy`; no registry, DB port, volume, provider credentials, or real pairing flow.

Fixed constants are test-only: admin `admin@dummy.local` / `DummyAdmin!234`, recovery `DUMMY-12345` (single use), device key `dummy-device-key`, customer OTP `123456`. Do not use them with staging or production.

The dummy's configured pair URL is `http://localhost:8080/admin/v1/devices/pair`; from an emulator use `10.0.2.2` instead of `localhost`. The dummy intentionally bypasses pairing with its fixed device key.

## Known handoff items

- A2 adds login and consumes the paired client plus `X-Device-Key`.
- A8 owns final Cloudflare pins and release signing.
- Instrumented tests require a connected device; the S9 script requires Docker and `adb`.
