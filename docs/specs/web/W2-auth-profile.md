# W2 — Customer Login, Session, and Profile

- Status: ready
- Depends on: W1, S2
- Contract: `docs/api/openapi.yaml` → Customer Auth, Customer profile, contact verification
- Next: W3

## Goal

Let customers sign in with a one-time code by email or SMS, keep a secure session, and manage their name, contact channels, and verification.

## Non-goals

- Menu browsing.
- Notification preferences (W7).
- Order history (W7).
- Password login.

## Screens and flows

### Sign-in

- Route: `/login`.
- Toggle: Email / Text.
- Email input or phone input with basic normalization guidance.
- Submit → request OTP.
- Code screen:
  - 6-digit input with paste support and numeric keyboard.
  - masked destination from response.
  - resend countdown using `resendAfterSeconds`.
  - change destination.
  - submit.
- Errors:
  - 429 → friendly rate-limit message with retry time.
  - invalid/expired code → inline error, keep the challenge until expired.
  - network → retry.
- On success:
  - set session query data.
  - clear sensitive local state.
  - redirect to the original protected route or home.

### Session

- `SessionProvider` querying `GET /api/v1/me`.
- Cookie-based; no tokens in JS.
- Bootstrap CSRF:
  - call `GET /api/v1/auth/csrf` before mutating requests.
  - store token in memory and read `bb_csrf` fallback.
- On 401:
  - attempt one refresh via `POST /api/v1/auth/refresh`.
  - if refresh fails, clear session and redirect to `/login?returnTo=...`.
- Logout:
  - call `POST /api/v1/auth/logout`.
  - clear query cache and local cart.
  - redirect home.

### Protected routes

- `RequireAuth` wrapper for checkout and account routes.
- `RequireProfile` for checkout: name required and at least one verified contact.
- Return-to path preserved.

### Profile

- Route: `/account`.
- Edit name.
- Email and phone:
  - display verified/unverified badge.
  - changing a contact starts verification:
    1. request code to new destination.
    2. enter code.
    3. update profile.
  - do not allow adding a destination owned by another account; show a generic conflict message.
- SMS consent status shown read-only here; editable in W7 preferences.
- Logout button.
- Account deletion instructions: contact support email (manual process).

## API integration

Operations:

- `getCsrfToken`
- `requestCustomerOtp`
- `verifyCustomerOtp`
- `refreshCustomerSession`
- `logoutCustomer`
- `getCustomerProfile`
- `updateCustomerProfile`
- `requestContactVerification`
- `confirmContactVerification`

## Validation

- Email: RFC-ish practical validation, max 254 chars.
- Phone: accept US formats; display E.164; server is source of truth.
- Code: exactly 6 digits.
- Name: 1–120 chars.
- Trim whitespace; do not silently change email case input, server normalizes.

## Ordered tasks

1. Add auth API functions and types.
2. Implement CSRF bootstrap and authenticated fetch behavior.
3. Implement SessionProvider and 401 refresh flow.
4. Build sign-in and code screens.
5. Add resend timer and rate-limit messaging.
6. Add protected route wrappers and return-to handling.
7. Build account/profile screen.
8. Build contact verification flow.
9. Add logout and cache clearing.
10. Add tests and MSW handlers.
11. Add Playwright auth smoke with a test OTP helper (local profile).

## Acceptance criteria

- Given an email, when requesting a code, then the code screen shows the masked destination and resend countdown.
- Given the local profile, when the test OTP is entered, then the customer is signed in and redirected.
- Given an expired code, then an inline error is shown and resend is available.
- Given 429, then the UI explains the wait and disables submit until allowed.
- Given a signed-in customer, when refreshing the page, then the session is restored via cookies.
- Given an expired access cookie, when a request returns 401, then one refresh succeeds and the request retries once.
- Given refresh failure, then the customer is redirected to login with return path.
- Given a protected checkout route, when signed out, then login is required and the return path is preserved.
- Given a new phone, when verified, then the profile shows verified and can be used for SMS.
- Given a name is missing, then checkout guard blocks with a profile link.
- Given logout, then the cart and query cache are cleared and protected routes redirect.

## Test plan

- Component tests for sign-in, code entry, resend timer, rate limit.
- Session refresh success/failure with MSW.
- Profile edit and contact verification.
- Protected route and return-to.
- Logout cache/cart clearing.
- Playwright: login → account → logout.
- Accessibility: labels, error announcements, focus management on code screen.

## Security checklist

- [ ] No tokens in localStorage/sessionStorage.
- [ ] CSRF header sent on all customer mutations.
- [ ] OTP not logged by the client.
- [ ] No account-existence messaging.
- [ ] Return-to paths restricted to local routes.
- [ ] No sensitive data in URL query strings.
- [ ] Logout clears client caches.
- [ ] Phone/email masked where displayed.

## Quality gate

This phase is not complete until `/phase-gate W2` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

Customers can sign in and manage verified contact info; session refresh is robust; tests pass; W5 can rely on `RequireProfile`.

## Handoff notes

- W7 adds notification preferences and order history.
- Local/test environments may expose an OTP test helper; never in prod.
- If the API changes cookie names, update the CSRF fallback.
