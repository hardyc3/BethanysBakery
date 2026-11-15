# A2 — Admin Login, Sessions, and Device Management

- Status: ready
- Depends on: A1, S2
- Contract: `docs/api/openapi.yaml` → Admin Auth, Admin Devices, `getAdminProfile`
- Next: A3

## Goal

Implement admin login with email, password, and TOTP/recovery code; secure token storage and refresh; device list/revocation; logout.

## Non-goals

- Menu/catalog screens.
- Password reset (CLI/admin recovery runbook).
- FCM registration (A8).
- App update checks (A8).

## Screens

### Login

- Email, password, TOTP code fields.
- Recovery code alternative.
- Show/hide password.
- Submit disabled until required fields are present.
- Errors:
  - generic invalid credentials
  - locked account with remaining time
  - network/TLS errors
  - 429 rate limit
  - expired/unpaired device → pairing screen
- After success → home placeholder with admin name.

### Session

- Access token attached as `Authorization: Bearer`.
- Device API key interceptor from A1.
- Refresh:
  - OkHttp Authenticator handles 401 once.
  - single-flight refresh (mutex) to avoid stampede.
  - on refresh success, retry original request once.
  - on refresh failure, clear tokens and go to login.
- Store tokens encrypted.
- Access TTL 15m, refresh 14d; do not assume client clock is correct.
- App foreground/background:
  - on resume, validate session with `GET /admin/v1/me` if last check > 5 minutes.
- Logout:
  - call endpoint, clear tokens, keep device pairing.
- Unpair:
  - clear device credentials and return to A1.

### Device management

- List devices:
  - name, platform, status, paired date, last seen, app version.
  - current device badge.
- Revoke another device with confirmation.
- Revoking the current device:
  - confirm strongly.
  - clear local pairing and return to A1.
- Certificate expiry warning from A1.

## Security

- Password never stored.
- TOTP code never logged/stored.
- Recovery code single-use; clear after submit.
- No screenshots on login? Use `FLAG_SECURE` on auth screens if practical.
- Generic error text for invalid credentials.
- Lockout and rate-limit messages without revealing account existence.
- Clipboard disabled for password/TOTP fields.
- Root/emulator detection is out of scope; document.

## API integration

- `loginAdmin`
- `refreshAdminSession`
- `logoutAdmin`
- `getAdminProfile`
- `listAdminDevices`
- `revokeAdminDevice`

## Ordered tasks

1. Build login UI and validation.
2. Implement login API and error mapping.
3. Add encrypted token storage.
4. Implement bearer interceptor and refresh authenticator with single-flight.
5. Add session validator on resume.
6. Build home placeholder with admin name.
7. Build device list and revoke flows.
8. Handle current-device revoke/unpair.
9. Add tests with MockWebServer.
10. Add login/device instrumented UI tests.

## Acceptance criteria

- Given valid credentials + TOTP, then login succeeds and tokens are stored.
- Given invalid password, then a generic error is shown.
- Given a locked account, then the lockout message appears.
- Given an expired access token, then refresh occurs once and the request succeeds.
- Given concurrent 401s, then only one refresh call is made.
- Given refresh failure, then tokens are cleared and login is shown.
- Given app restart, then the session persists until refresh expires.
- Given logout, then tokens are cleared and device pairing remains.
- Given another device, when revoked, then it disappears/marks revoked after refresh.
- Given current device revoke, then credentials are cleared and pairing is required.
- Given a revoked device at the server, then API calls return 401 and the app returns to pairing/login.

## Test plan

- MockWebServer: login success, 401, 423/429, problem parsing.
- Token refresh success, reuse failure, concurrent 401s.
- Session resume check.
- Device list/revoke success/error.
- UI tests: validation, error states, current-device confirmation.
- Manual: login against staging with TOTP, revoke from another device.

## Security checklist

- [ ] Tokens encrypted at rest.
- [ ] Password/TOTP not logged or stored.
- [ ] Bearer + device key on requests.
- [ ] Single-flight refresh.
- [ ] Logout revokes server session.
- [ ] Current-device revoke clears local secrets.
- [ ] Generic auth errors.
- [ ] Auth screens `FLAG_SECURE` if practical.

## Quality gate

This phase is not complete until `/phase-gate A2` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

Admin can log in, stay signed in securely, manage devices, and recover from revoked/expired sessions.

## Handoff notes

- A3 starts after login.
- A8 adds FCM token registration; device ID is available from secure storage.
- If the API adds TOTP enrollment/reset endpoints later, update this spec.
