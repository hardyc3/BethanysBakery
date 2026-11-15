# A8 — Push Notifications, App Updates, and Release

- Status: ready
- Depends on: A7, S6, S7
- Contract: `updateAdminPushToken`, `getAdminAppInfo`, FCM payloads
- Next: production readiness

## Goal

Deliver admin push notifications, deep-link into relevant screens, enforce minimum app versions, and produce a signed sideloadable release APK with documented operations.

## Non-goals

- Play Store publishing.
- iOS admin app.
- Customer push notifications.
- Crash analytics SaaS (optional, privacy review required).

## FCM

- Firebase project for the admin app.
- `google-services.json` supplied locally; not committed if it contains project secrets. Document how to obtain it.
- Notification channels:
  - `orders` — new order, high importance.
  - `system` — failures/updates, default importance.
- On login/startup:
  - get FCM token.
  - `PUT /admin/v1/devices/{deviceId}/push-token` with token + app version.
  - refresh token on rotation.
- On logout:
  - clear token server-side (`token: null`) and locally.
- On device revoke/unpair:
  - clear token.
- Handle `onNewToken` while logged out: store pending and register after login.
- Payload from server (data message):
  ```json
  {
    "type": "NEW_ORDER",
    "orderId": "...",
    "menuDateId": "...",
    "orderNumber": "BB-20261120-0001"
  }
  ```
- Notification display:
  - title/body from server data with safe fallbacks.
  - no PII beyond order number/first name.
  - tap opens order detail when authenticated; otherwise login with pending deep link.
- Foreground:
  - show in-app snackbar/banner and refresh relevant queries.
- Background:
  - system notification.
- Deduplicate by notification ID/order ID within a short window.
- If push fails, server sends admin email fallback; app does not need to handle email.

## Deep links

- `bethanysbakeryadmin://orders/{orderId}`
- `bethanysbakeryadmin://menu-dates/{menuDateId}`
- `bethanysbakeryadmin://notifications`
- Validate IDs and handle missing/revoked objects gracefully.

## App update check

- `GET /admin/v1/app-info` on launch and resume, after pairing and before login.
- Compare `versionCode`/`versionName` against the returned semver strings:
  - below `minVersion` → blocking screen with download button; no API actions.
  - below `latestVersion` → dismissible "Update available".
- Download URL opens browser/download manager.
- Store last-check time; do not block on network failure if current session remains valid.
- Never auto-install.

## Release build

- Versioning: `versionCode` monotonic, `versionName` semver.
- Signing:
  - release keystore outside the repo.
  - env/properties injected by local Gradle config; never commit secrets.
  - document keystore backup and certificate fingerprint.
- Build:
  - `minifyEnabled true`, resource shrinking.
  - ProGuard/R8 rules for serialization, Retrofit, BouncyCastle, ML Kit.
  - remove debug logging.
  - disable debug pin bypass.
  - `network_security_config` cleartext false.
  - release pin set with primary + backup pins.
- Manifest:
  - no unnecessary permissions.
  - camera permission for QR/photo.
  - notifications permission (Android 13+).
  - file provider if needed for photos.
- Crash handling:
  - top-level handler logs sanitized stack traces locally.
  - no automatic upload unless approved.
- APK distribution:
  - signed APK + SHA-256 checksum.
  - host at a documented URL.
  - install instructions for sideloading.
  - version/release notes.

## Testing

- Unit: FCM payload parsing, deep link parsing, version comparison, token registration.
- Instrumented: notification channel creation, permission request, notification tap routing.
- MockWebServer: push token registration/clear, app-info gate.
- Manual:
  - FCM test message to foreground/background/killed app.
  - deep link to order/menu/notifications.
  - update gate with fake versions.
  - install signed APK on a clean device.
  - revoke device and confirm push stops.

## Security and privacy

- Push payloads contain no sensitive details.
- Token stored encrypted; cleared on logout/unpair.
- Notification content hidden on lock screen? Provide a setting or default to generic text. Spec: generic on lock screen.
- Pins cannot be disabled in release.
- No world-readable files.
- Exported components minimal; deep links validated.
- Keystore/signing secrets not in git.
- ProGuard does not strip security-critical checks.

## Ordered tasks

1. Add Firebase dependencies and local config docs.
2. Implement FCM service, channels, token registration/clear.
3. Implement payload parsing and notification display.
4. Add deep links and navigation handling.
5. Add in-app foreground refresh/banner.
6. Add app-info query and version gate UI.
7. Add release build config, R8 rules, signing docs.
8. Add network security config and final pins.
9. Add unit/instrumented/manual tests.
10. Produce release checklist and APK checksum instructions.

## Acceptance criteria

- Given a logged-in device, then its FCM token is registered with the server.
- Given logout, then the token is cleared server-side and locally.
- Given a new order push, then a notification appears and tapping opens the order.
- Given a push while logged out, then login occurs and the order opens after auth.
- Given a foreground push, then the orders list refreshes and a banner appears.
- Given an invalid deep link, then a safe error is shown.
- Given an unauthenticated but paired device, then `getAdminAppInfo` succeeds and the update gate runs before login.
- Given a version below `minVersion`, then a blocking update screen appears.
- Given a version below `latestVersion`, then a dismissible update prompt appears.
- Given a release APK, then it installs, uses mTLS + pinning, and has no debug logging.
- Given a revoked device, then push tokens are invalidated and API calls fail to login/pairing.
- Given a clean device, then QR pairing → login → order notification works end to end.

## Test plan

- Unit tests for payload/deep link/version logic.
- MockWebServer token registration, app-info.
- Instrumented notification channel/permission/tap.
- Manual FCM foreground/background/killed.
- Clean-device release install and pairing.
- Pin failure and rotation drill.
- Revocation drill.
- Accessibility: update screen, notification content.

## Security checklist

- [ ] `google-services.json` handling documented; no secrets committed.
- [ ] Push payloads contain no sensitive PII.
- [ ] Token encrypted and cleared on logout/unpair.
- [ ] Lock-screen content generic.
- [ ] Release pinning enabled with backup pin.
- [ ] Cleartext disabled.
- [ ] R8 rules preserve security checks.
- [ ] Signing keys outside repo and backed up.
- [ ] Deep links validated.
- [ ] Exported components minimal.
- [ ] No debug bypass in release.

## Quality gate

This phase is not complete until `/phase-gate A8` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

Admin receives actionable push notifications, deep links work, minimum versions are enforced, and a signed release APK is documented, reproducible, and verified on a clean device.

## Handoff notes

- Coordinate final pins and release URL with S7 runbooks.
- Server FCM payload changes require a spec/OpenAPI update.
- Keep the release keystore and `google-services.json` in the operator's secret store.
