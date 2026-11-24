# A8 — Deep Links, App Updates, and Release (FCM removed in S8)

- Status: ready
- Depends on: A7, S6, S7
- Contract: `getAdminAppInfo` (FCM/push-token operations removed in S8)
- Next: production readiness
- Superseded by S8: Firebase/FCM admin push is gone. Sections marked VOID are historical and MUST NOT be implemented; see below and the S8 follow-up register row "A7/A8 client".

## Goal

Deliver deep-links into relevant screens, enforce minimum app versions, and produce a signed sideloadable release APK with documented operations. Admin new-order alerts are server-side Mailgun email plus optional Textbee SMS (S8), not in-app push.

## S8 supersession (authoritative)

S8 removed Firebase/FCM entirely and deleted `updateAdminPushToken`, `PushTokenRequest`, and `fcmRegistered`. The `## FCM` section and every FCM / push-token / notification-channel item in Testing, Ordered tasks, Acceptance criteria, Test plan, and Security checklist below are **VOID**. Keep mTLS device pairing, the one-active-device rule, and path-scoped WAF rules on the single hostname. No edge certificate pinning rotation runbook remains; Cloudflare owns HTTPS certificates.

## Non-goals

- Play Store publishing.
- iOS admin app.
- Customer push notifications.
- Admin push notifications / Firebase / FCM (removed in S8; admin alerts are server-side email/SMS).
- Crash analytics SaaS (optional, privacy review required).

## FCM — VOID (removed by S8)

Historical only; do not implement. No Firebase project, no `google-services.json`, no `onNewToken`/token registration or clear, no `NEW_ORDER` data message, no push channels or notification display, no foreground banner driven by push. Admin new-order alerting is server-side email/SMS. Deep links below remain in scope.

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

- Unit: deep link parsing, version comparison.
- Instrumented: deep-link tap routing.
- MockWebServer: app-info gate.
- Manual:
  - deep link to order/menu/notifications.
  - update gate with fake versions.
  - install signed APK on a clean device.
  - revoke device and confirm admin API access fails.

## Security and privacy

- Pins cannot be disabled in release.
- No world-readable files.
- Exported components minimal; deep links validated.
- Keystore/signing secrets not in git.
- ProGuard does not strip security-critical checks.

## Ordered tasks

1. Add deep links and navigation handling.
2. Add app-info query and version gate UI.
3. Add release build config, R8 rules, signing docs.
4. Add network security config and final pins (no pin-rotation runbook; Cloudflare owns HTTPS certs).
5. Add unit/instrumented/manual tests.
6. Produce release checklist and APK checksum instructions.

## Acceptance criteria

- Given an invalid deep link, then a safe error is shown.
- Given an unauthenticated but paired device, then `getAdminAppInfo` succeeds and the update gate runs before login.
- Given a version below `minVersion`, then a blocking update screen appears.
- Given a version below `latestVersion`, then a dismissible update prompt appears.
- Given a release APK, then it installs, uses mTLS + pinning, and has no debug logging.
- Given a revoked device, then API calls fail to login and re-pairing is blocked by the one-active-device rule.
- Given a clean device, then QR pairing → login → deep link to an order works end to end.
- S8 VOID (do not implement): FCM token registration/clear, new-order/foreground/background push notification display. Admin new-order alerts are email/SMS.

## Test plan

- Unit tests for deep link/version logic.
- MockWebServer app-info.
- Instrumented deep-link tap.
- Clean-device release install and pairing.
- Pin failure drill (no rotation runbook; Cloudflare owns HTTPS certs).
- Revocation drill.
- Accessibility: update screen, notification content.

## Security checklist

- [ ] No Firebase/FCM config, push token, or push payload handling remains (S8).
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

Deep links work, minimum versions are enforced, and a signed release APK is documented, reproducible, and verified on a clean device. Admin new-order alerts are email/SMS (S8); no FCM requirement remains.

## Handoff notes

- S8 removes Firebase/FCM entirely: no Firebase project, no `google-services.json`,
  no push token registration (`PUT /admin/v1/devices/{deviceId}/push-token`), and
  no push payload handling. Admin alerts are Mailgun email plus optional Textbee
  SMS (`ADMIN_ALERT_EMAIL` / `ADMIN_ALERT_PHONE`).
- Keep mTLS device pairing: client certificate, API key, and the one-active-device
  rule. `/admin/v1/devices/pair` is the mTLS-exempt path on the one hostname.
- No edge certificate pinning rotation runbook remains after S8; coordinate the
  release URL and signing with the owner, not a pin-rotation procedure.
- Keep the release keystore in the operator's secret store.
