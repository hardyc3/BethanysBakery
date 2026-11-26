# A1 — Admin App Scaffold, Networking, and Device Pairing

- Status: complete
- Depends on: S1, S2, S9
- Contract: `docs/api/openapi.yaml` → `pairAdminDevice`, `listAdminDevices`; `docs/specs/server/S9-observability-audience-preview-test-image.md` → dummy server image
- Next: A2

## Goal

Create the Kotlin Android admin app foundation and the one-time QR pairing flow that installs a device client certificate, private key, and API key, then configures mTLS + pinned networking.

## Non-goals

- Admin login (A2).
- Menu/catalog screens (A3–A5).
- FCM (removed in S8; admin alerts are server-side email/SMS, no A8 push client).
- Play Store distribution.

## Deliverables

- `android-admin/` Gradle scaffold with debug/release builds, Compose/Material 3, Hilt, navigation, and the core packages below.
- Keystore-backed EC P-256 identity, CSR generation, encrypted credential storage, and unpair cleanup.
- QR scanner plus manual-entry pairing with exact build-time URL allowlist.
- mTLS + SPKI pinning client, Problem Details mapping, and certificate-expiry UI.
- Unit, instrumented, and Docker-backed happy-path integration tests; README and handoff notes.

## API integration

- `pairAdminDevice` (`POST /admin/v1/devices/pair`): sends `pairingCode`, `deviceName`, `publicKeyPem`, `csrPem`, `appVersion`; stores certificate chain and API key from the response.
- `listAdminDevices` (`GET /admin/v1/devices`): A1-owned post-pairing smoke call, using `X-Device-Key` + admin bearer in tests; no A8 `app-info` dependency.
- RFC 9457 Problem Details map to `ApiError(code, status, title, detail, fieldErrors, requestId)`.

## UI screens/components

- Pairing screen with scan/manual entry, field validation, and actionable error messages.
- CameraX + ML Kit QR scanner screen.
- Post-pairing placeholder screen with unpair confirmation dialog.
- Certificate status badge with exact expiring warning text.
- Loading, error, and retry primitives; snackbar for transient results.

## Project setup

- `android-admin/` Gradle project, Kotlin DSL.
- Kotlin, Jetpack Compose, Material 3.
- Hilt.
- Retrofit + OkHttp + Kotlin serialization.
- Navigation Compose.
- Coroutines/Flow.
- CameraX + ML Kit barcode scanning for QR.
- AndroidX Security/Keystore (no deprecated EncryptedSharedPreferences if avoidable; use a Keystore-wrapped AES key with DataStore).
- Min SDK 26, target/compile latest stable.
- Build types: `debug`, `release`.
- Package: `com.bethanysbakery.admin`.
- App name: "Bethany's Bakery Admin".

## Modules/packages

```text
app/
  core/network
  core/security
  core/storage
  core/ui
  core/error
  feature/pairing
  feature/login (placeholder)
  navigation
```

## Design system

- Warm bakery theme, light/dark support.
- Large touch targets for kitchen use.
- Clear status colors with text labels (not color alone).
- Loading, empty, error, retry components.
- Snackbar for transient results; dialogs for destructive actions.

## Pairing flow

### Entry

- First launch with no pairing → pairing screen.
- Button "Scan pairing QR".
- Manual entry fallback:
  - pair URL
  - API URL
  - pairing code
- QR payload:
  ```json
  {"v":1,"pairUrl":"https://bakery.cherry-home.com/admin/v1/devices/pair","apiUrl":"https://bakery.cherry-home.com/admin/v1","code":"..."}
  ```
  S8 removed the separate `bakery-pair.cherry-home.com` and
  `bakery-admin-api.cherry-home.com` hostnames. Both URLs now use
  `bakery.cherry-home.com`; pairing stays at `/admin/v1/devices/pair` and admin
  API calls stay under `/admin/v1` with mTLS after pairing.
- Validate URL scheme/host/path allowlist:
  - Release requires `https` and exact match against build-time `APP_PAIR_URL`
    and `APP_ADMIN_API_URL` on the one hostname.
  - The QR `pairUrl` must equal the configured `APP_PAIR_URL`; the QR `apiUrl` must equal the configured `APP_ADMIN_API_URL`. No other hosts, ports, or paths are accepted.
  - Build config injects both exact URLs; they are not editable at runtime.
  - Debug may additionally allow `http://10.0.2.2:<port>` and a documented LAN host from a debug-only list.
  - Reject userinfo, fragments, non-default ports in release, and any host or path not on the allowlist.
- Reject unknown `v`.

### Key and certificate

- Generate EC P-256 keypair in Android Keystore, non-exportable.
- Build a PKCS#10 CSR with BouncyCastle using the Keystore key.
- POST `pairUrl` (the mTLS-exempt pairing endpoint):
  - `pairingCode`, `deviceName` (default `Build.MODEL`), `publicKeyPem`, `csrPem`, `appVersion`.
  - Subsequent `/admin/v1` calls use the installed client certificate.
- Response:
  - `deviceId`
  - `apiKey`
  - `clientCertificatePem`
  - `caCertificatePem`
  - `certificateExpiresAt`
- Store:
  - private key remains in Keystore alias `device_key`.
  - client cert chain in a Keystore entry or encrypted file.
  - API key encrypted with a Keystore AES-GCM key.
  - deviceId, API URL, cert expiry in DataStore.
- Build a KeyManager-backed TLS stack from the Keystore private key and encrypted client certificate chain for OkHttp.
- Trust: system CA for Cloudflare TLS; optional CA cert for local mTLS testing.
- Pairing is one-time. On success navigate to login placeholder.

### Networking

- OkHttp:
  - base URL from pairing.
  - client certificate KeyManager.
  - interceptor adds `X-Device-Key`.
  - interceptor adds `X-App-Version: BuildConfig.VERSION_NAME`.
  - interceptor adds `Accept: application/json`.
  - request ID logging.
  - no cleartext in release (`network_security_config`).
  - timeouts: connect 10s, read 30s.
- Certificate pinning:
  - `ADMIN_API_PINS` build config, comma-separated SPKI SHA-256 pins.
  - at least one primary and one backup pin in release.
  - debug may disable with an explicit flag.
  - require two pins at build time; do not hardcode a single pin with no backup.
  - final pin values, signing, and deployment rotation belong to A8/Cloudflare; A1 does not publish a rotation runbook.
- Error mapping:
  - Problem Details → `ApiError(code, status, title, detail, fieldErrors, requestId)`.
  - network/timeout/TLS errors → actionable messages.
  - Client-local fallback codes (`TLS_*`, `TIMEOUT`, `NETWORK_UNAVAILABLE`, `EMPTY_RESPONSE`, `INVALID_STATE`, `INTERNAL_ERROR`, `CANCELLED`, `UNEXPECTED_ERROR`) are not new server Problem codes; server codes come from `docs/api/openapi.yaml`.
- Certificate expiry:
  - if client cert expires within 14 days, show "Device certificate expiring; re-pair required".
  - expired → pairing screen.

### Secure storage

- API key and refresh/access tokens encrypted at rest.
- Private key never exportable.
- No secrets in logs, crash reports, or screenshots.
- Clear all on unpair.

## Local development

- Build config:
  - `APP_PAIR_URL`, `APP_ADMIN_API_URL`, `ADMIN_API_PINS`.
- Debug emulator can use `10.0.2.2`.
- Local API profile can bypass mTLS, but still requires a device key.
- Provide a debug-only "seed pairing" path only if it uses a real pair endpoint; do not fake auth.
- Functional tests start the S9 dummy server image locally; use its fixed device key and auth constants. Seed all menus/orders/preferences through the real API, never direct database access. MockWebServer remains for unit-level HTTP tests.

## Ordered tasks

1. Scaffold Gradle/Compose/Hilt/navigation/theme.
2. Add DataStore + Keystore secure storage.
3. Implement QR scanner and manual entry.
4. Generate EC keypair and CSR.
5. Implement pair API and error handling.
6. Store certificate/API key and build OkHttp mTLS client.
7. Add pinning config and network security config.
8. Add certificate expiry checks.
9. Add placeholder post-pairing screen.
10. Add unit/instrumented tests and README.
11. Add debug/local instructions.
12. Wire functional tests to the S9 dummy image with API-only seeding.

## Acceptance criteria

- Given a valid pairing QR, when scanned, then the app pairs, stores the certificate/key/API key, and shows the post-pairing screen.
- Given a QR `pairUrl` or `apiUrl` whose host or path is not on the build-time allowlist, then it is rejected before any network call.
- Given an invalid/expired code, then a clear error is shown and no credentials are stored.
- Given a malformed QR, then it is rejected without a network call.
- Given a release build with an `http` URL, then pairing is rejected.
- Given a paired device, when making a request, then the client certificate and `X-Device-Key` are sent.
- Given a TLS/pinning failure, then a specific error is shown.
- Given an expiring certificate, then a warning appears.
- Given app restart, then pairing persists.
- Given unpair, then keys/certs/tokens are removed.
- Given debug, then the local API URL can be used; release requires HTTPS.
- Given the S9 dummy image in functional tests, then the app starts it, authenticates with the fixed device key, seeds state through the API, and needs no real pairing or provider credentials.

## Test plan

- Unit: QR payload parsing, URL validation, error mapping, pin config, backup-pin rotation.
- Instrumented: Keystore key creation, CSR generation, certificate chain loading, DataStore persistence.
- MockWebServer: pair success/failure/429/problem+json.
- TLS test: client cert presented and API key header present.
- Docker integration: `S9DummyServerIntegrationTest` starts the locally built dummy container and covers readiness/config, customer auth + profile + preferences + menu/order + admin order/preview, and the A1 paired client's `listAdminDevices` round-trip. Run with `./gradlew testDebugUnitTest -PincludeDockerIntegration=true --tests '*S9DummyServerIntegrationTest'`; normal `./gradlew test` excludes the `docker-integration` tag.
- Functional script: start S9 dummy image, seed via API, verify authenticated request round-trip on a device/emulator.
- Manual: scan QR generated by `infra/scripts/pair-device.sh`, pair against the approved local/dummy API (no staging host exists), restart, unpair.
- Security: no secrets in logcat; certificate non-exportable.

## Security checklist

- [x] HTTPS only in release. Evidence: `android-admin/app/src/main/java/com/bethanysbakery/admin/feature/pairing/PairingUrlValidator.kt`, `network_security_config.xml`, release build with no pins blocked in `app/build.gradle.kts`.
- [ ] EC P-256 key in Keystore, non-exportable. Static evidence: `core/security/DeviceKeyManager.kt`; instrumented proof in `DeviceKeyManagerInstrumentedTest.kt` still needs a host with `adb` (deferred to merge-time device run).
- [x] API key encrypted at rest. Evidence: `core/security/EncryptedFileStore.kt`, `core/storage/PairedDeviceStore.kt`.
- [x] Pinning with backup pins. Evidence: `core/network/AdminHttpClientFactory.kt`, release guard requiring two pins in `app/build.gradle.kts`, pinning unit tests.
- [x] No cleartext traffic. Evidence: `network_security_config.xml`; debug-only HTTP allowlist in `PairingUrlValidator.kt`.
- [ ] No secrets in logs/screenshots. Static evidence: no secret logging in `AdminHttpClientFactory.kt`, `FLAG_SECURE` in `MainActivity.kt`; dynamic logcat check deferred to device run.
- [x] Pair code single-use. Server-enforced; A1 client sends code once and clears it on success (`PairingViewModel.kt`), with server audit dependency in `DevicePairingService.java`.
- [ ] Unpair clears credentials. Static evidence: `feature/pairing/UnpairUseCase.kt`; instrumented proof in `UnpairUseCaseInstrumentedTest.kt` still needs a host with `adb` (deferred to merge-time device run).
- [x] Certificate expiry handled. Evidence: `core/security/CertificateExpiryChecker.kt`, `core/ui/components/StatusComponents.kt`.
- [x] QR/URL allowlist. Evidence: `PairingUrlValidator.kt`, `PairingInputParserTest.kt`.

## Quality gate

This phase is not complete until `/phase-gate A1` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description and are recorded below.

### A1 gate results

| Agent | Status | Findings | Action |
|---|---|---|---|
| `spec-compliance` | PASS | Initial blockers: unchecked security checklist; A8 `app-info` operation used; staging manual test contradicted architecture; gate results missing. | Removed `app-info` in favor of A1-owned `GET /admin/v1/devices`; changed manual step to local/dummy; recorded checklist and gate table; final PR description must include this table. |
| `lint` | PASS | Initial blockers: stale snackbar path after refactor; CRLF in `gradlew.bat`. Warnings: deprecated backup rule, obsolete icon qualifier, unused color, no resource shrinking, missing monochrome icon. | Removed stale snackbar path; normalized wrapper LF; added `dataExtractionRules`; removed unused color; enabled resource shrinking; added monochrome icons; kept `mipmap-anydpi-v26` because moving to plain `anydpi` breaks AAPT. BouncyCastle dependency warning and dummy-only literals accepted. |
| `refactor` | PASS | Blockers: duplicate manual QR parser and dead post-pairing message path. | `onManualSubmit` now uses `PairingInputParser.parseManual`; removed dead snackbar/message code. Warnings on strings/UI splitting are deferred to A2 UI work. |
| `test-quality` | PASS (coverage waiver) | Initial blockers: temporary Gradle DSL compile error; no coverage tooling; failed-pairing cleanup, exact warning text, and server happy paths unproven. | Fixed Gradle DSL; 41 JVM unit tests pass; added three Docker integration tests for S9 happy paths and the A1 paired-client round-trip; added `PairingViewModelFailureTest`, `CertificateStatusBadgeTest`, config/error/pinning/parser cases. JaCoCo not configured; no coverage percentage claim. Device execution is unavailable here (`adb` missing), so instrumented tests are compile-verified only. |
| `security` | PASS (device-check waiver) | Initial blockers: task-name pin guard bypass and checklist claims ahead of device evidence. | Release validation now runs in variant scope for release-producing tasks, requires two valid `sha256/<base64>` pins, HTTPS/default-port/same-host URLs. Raw server error detail no longer shown; stored API URL is revalidated and full unpair on invalid URL/failed pairing; shell JSON/header values are escaped. Dynamic logcat/non-exportable checks remain merge-time device evidence; dependency verification and signing are A8. |
| `gap-analysis` | PASS | Blockers: CLI QR contract mismatch (`pairUrl?code=` vs JSON), local/dummy URL drift, release guard broke debug tests, gate table/status missing, no automated server-container happy path. | CLI now renders the exact JSON QR payload; local/dummy/env URLs use `/admin/v1/devices/pair` and `/admin/v1`; release guard skips non-release tasks; `S9DummyServerIntegrationTest` covers server happy paths; status complete and gate table recorded. A2 seams routed in handoff notes. |

Evidence: `cd android-admin && ./gradlew test :app:compileDebugAndroidTestKotlin --no-daemon` (41 unit tests pass); `./gradlew testDebugUnitTest -PincludeDockerIntegration=true --tests '*S9DummyServerIntegrationTest' --no-daemon` (3 Docker integration tests pass); `./gradlew lintDebug assembleRelease lintRelease --no-daemon` with two valid pins (pass); `./gradlew assemble --no-daemon` with no pins (fails as designed); `API_ONLY=1 ./android-admin/scripts/functional-test.sh` (S9 API seed pass); `cd server && ./gradlew test --tests '*PairDeviceCommandTest' --no-daemon` (pass). Connected-device instrumentation and the emulator install leg of the S9 script require a host with `adb` and are deferred to merge-time verification by the A1 owner.

## Definition of done

The app builds, pairs against the API, persists credentials, and can make an authenticated mTLS request. A2 adds login.

## Handoff notes

- A2 consumes the paired client, `DeviceApiKeyProvider`, `AuthenticatedApiProvider`, `TokenStore`, and global `FLAG_SECURE`; resume-time certificate expiry remains A2 work.
- A8 finalizes pin values and release signing (no FCM; removed in S8).
- Pairing QR is generated by `infra/scripts/pair-device.sh` (S2/S7).
- Dummy image is built locally from `server/Dockerfile --target dummy`; no registry dependency. Do not use its fixed secrets with a prod or staging API.
- S9 dummy functional-test contract: API `8080`, health/scrape `9090`; fixed device key `dummy-device-key`; no real pairing flow. Admin login uses `admin@dummy.local` / `DummyAdmin!234` with current TOTP from `JBSWY3DPEHPK3PXP`, or recovery code `DUMMY-12345`; customer OTP is `123456`. CSRF comes from `GET /api/v1/auth/csrf` (`bb_csrf` cookie + `X-CSRF-Token`). Seed every menu/order/preference through the real API, never the embedded DB.
