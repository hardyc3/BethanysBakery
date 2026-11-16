# S6 — Payments and Notifications

- Status: ready
- Depends on: S5
- Contract: `docs/api/openapi.yaml` → payment endpoint, notification preferences, Admin Notifications
- Next: S7

## Goal

Implement manual payment tracking, Venmo instructions, email/SMS/push delivery, notification preferences, scheduling, retries, idempotency, and the admin notification queue.

## Non-goals

- Automated Venmo verification or refunds.
- Customer web or Android notification UI (W6/W7/A7/A8).
- FCM client setup (A8). This phase issues pushes and stores tokens.
- Cloudflare/mTLS deployment (S7).

## Data model — `V6__notifications.sql`

Create exactly as described in `docs/domain-model.md`:

- `notification_preference`
- `notification`
- `notification_delivery`
- `outbox_event`

`V5` already creates all order payment/delay columns. `V6` only adds:

- `admin_device.fcm_token`
- `admin_device.fcm_updated_at`

Constraints/indexes:

- Unique `(customer_id, event_type)`.
- Unique `notification.idempotency_key`.
- `notification(status, scheduled_at)` index.
- `notification_delivery(notification_id)` index.
- `outbox_event(status, available_at)` index.

## Payments

Endpoint: `PUT /admin/v1/orders/{orderId}/payment`.

Request: `{ paid: boolean, method: VENMO|CASH|OTHER|null }`.

Rules:

- Allowed for `PENDING`, `ACCEPTED`, `READY`, `COMPLETED`.
- `paid=true` sets `payment_status=PAID`, `paid_at=now`, and `payment_method`. A null method defaults to `VENMO`; cash at pickup uses `CASH`.
- `paid=false` sets `UNPAID`, clears `paid_at`/method. This is a correction and must be audited.
- No customer-facing cash option.
- Audit `ORDER_PAID` / `ORDER_PAYMENT_CORRECTED`.
- No automatic customer notification for payment status; customer sees it in the app.
- On transition to `PAID` (`paid=true`), queue an admin `ORDER_PAID` email to `ADMIN_ALERT_EMAIL` in the same transaction as the payment update/outbox write. This is a durable digital paper trail for bake-day operations if the database later fails.
- `ORDER_PAID` email content must include everything needed to bake and fulfill: order number, bake date, pickup window, customer name and contact snapshots, item lines with quantities, selected options/customizations, line/order notes, totals, payment status/method, and `paid_at`.
- On correction to `UNPAID` (`paid=false`), do not send `ORDER_PAID`; audit only. Duplicate paid requests/retries must be deduped by `order:{orderId}:paid` (v1: do not resend if already sent/queued for that order).

Venmo instructions are built from config:

- `VENMO_HANDLE`
- `VENMO_DISPLAY_NAME`
- `APP_PUBLIC_BASE_URL`
- `orders.total_cents`
- `orders.order_number`

`PaymentInstructions` includes `venmoHandle`, `venmoDisplayName`, amount, note (`BB-...`), and optional `venmoDeepLink`.

## Notification architecture

### Outbox

- Transactional outbox required for business events: write `outbox_event` (and notification rows when feasible) in the same transaction as the business change. `outbox_event` is the durable source of truth.
- S5 writes `OrderCreated` outbox row in the order transaction. S6 handlers/processors consume it.
- A scheduler polls `outbox_event` where `status='PENDING'` and `available_at <= now`.
- Processing idempotent via `idempotency_key`.
- A second scheduler dispatches due `notification_delivery` rows.
- Failures set `FAILED`, increment attempts, set `available_at` with exponential backoff:
  - 1 minute, 5 minutes, 15 minutes, 1 hour, 6 hours.
  - After 5 attempts, leave `FAILED`.
- Admin can retry a failed notification.

### Events

| Event | Recipient | Timing |
|---|---|---|
| `MENU_PUBLISHED` | opted-in customers | menu `notifyAt` |
| `ORDER_REMINDER` | opted-in customers with no `PENDING`, `ACCEPTED`, `READY`, or `COMPLETED` order on that date | 09:00 day before |
| `ORDER_ACCEPTED` | order customer | immediately |
| `ORDER_READY` | order customer | immediately |
| `ORDER_DELAYED` | order customer | admin action |
| `ORDER_REJECTED` | order customer | immediately |
| `ORDER_CANCELLED` | order customer | immediately |
| `ORDER_EXPIRED` | order customer | immediately |
| `MENU_CANCELLED` | opted-in customers + active order customers | immediately |
| `ORDER_PAID` | admin (`ADMIN_ALERT_EMAIL`) | immediately on transition to `PAID` |
| Admin new order | admin | immediately |
| Admin push fallback | admin email | when no active FCM token or push fails for new order |

Idempotency keys:

- `MENU_PUBLISHED:<menuId>`
- `ORDER_REMINDER:<menuDateId>:<customerId>`
- `<ORDER_EVENT>:<orderId>` for `ORDER_ACCEPTED`, `ORDER_READY`, `ORDER_DELAYED`, `ORDER_REJECTED`, `ORDER_CANCELLED`, `ORDER_EXPIRED`
- `order:{orderId}:paid` for the admin paid-order email (one per paid transition; payment correction to `UNPAID` does not send)
- `MENU_CANCELLED:<menuId>:<customerId>` for menu cancellation, or `MENU_CANCELLED:<menuDateId>:<customerId>` for date cancellation
- `ADMIN_NEW_ORDER:<orderId>` for push and `ADMIN_NEW_ORDER_EMAIL:<orderId>` for the fallback email

These keys are unique per logical message and channel fallback; retries reuse the same key.

### Preferences

Customer:

- `GET/PUT /api/v1/me/notification-preferences`.
- Defaults exactly as in `docs/domain-model.md`.
- `GET` includes `smsOptedOut` so the UI can show STOP state separately from consent.
- Enabling SMS requires `smsConsent=true`; server records `sms_consent_at`.
- An explicit `PUT` with `smsConsent=true` is a new consent and clears `sms_opt_out_at`.
- SMS is suppressed if no consent or `sms_opt_out_at` is set.
- `PUT` validates event types and booleans.

### Suppression rules

- SMS:
  - consent required
  - not opted out
  - destination present
  - `MENU_PUBLISHED` and `ORDER_REMINDER` SMS only between 09:00–20:00 Denver. If queued/scheduled outside this window, defer delivery until next 09:00 Denver (or suppress if bake date passed). Enforce in dispatcher, not just scheduler.
  - Transactional order-status SMS (`ORDER_ACCEPTED`, `ORDER_READY`, `ORDER_DELAYED`, `ORDER_REJECTED`, `ORDER_CANCELLED`) may send when event occurs.
- Email:
  - verified email present
  - preference enabled
- Push:
  - active device with FCM token
  - device revocation clears token (S2 revoke handler extended in this phase)
- Deduplicate by idempotency key.

### Schedulers

- `menuNotificationScheduler`: every minute.
  - Find `PUBLISHED` menus with `notification_enabled=true`, `notify_at <= now`, `notify_sent_at is null`.
  - Queue `MENU_PUBLISHED` for opted-in customers.
  - Set `notify_sent_at` after queueing.
- `reminderScheduler`: every minute.
  - For each published date where `bake_date - 1 day 09:00 <= now`, `reminder_sent_at is null`, and now < bake date start.
  - Queue `ORDER_REMINDER` for opted-in customers with no `PENDING`, `ACCEPTED`, `READY`, or `COMPLETED` order on that date.
  - Set `reminder_sent_at` after queueing.
- `notificationDispatchScheduler`: every 30–60 seconds.
- `outboxScheduler`: every 10–30 seconds.
- All schedulers use `Clock` injection and are disabled in tests unless explicitly started.

### Providers

Interfaces:

```java
public interface EmailSender { DeliveryResult send(EmailMessage message); }
public interface SmsSender { DeliveryResult send(SmsMessage message); }
public interface PushSender { DeliveryResult send(PushMessage message); }
```

Implementations:

- Mailgun HTTP API:
  - `MAILGUN_API_KEY`, `MAILGUN_DOMAIN`, `MAILGUN_API_BASE`, `MAILGUN_FROM`.
  - Basic auth `api:<key>`.
  - Timeout and retry per notification policy.
- Textbee:
  - `TEXTBEE_BASE_URL`, `TEXTBEE_API_KEY`, `TEXTBEE_DEVICE_ID`.
  - POST send message.
  - Parse provider message ID.
- FCM HTTP v1:
  - `FCM_PROJECT_ID`, `FCM_SERVICE_ACCOUNT_JSON` (inline or file path).
  - OAuth2 service account token.
  - Send to active device tokens.
  - Remove/disable token on `UNREGISTERED`/`INVALID_ARGUMENT`.
- Local:
  - `local` profile logs rendered messages and OTP codes to console.
  - No network calls.
- Missing configuration:
  - Outside `local`, if a required provider credential is absent, admin-triggered send/retry operations return 503 `NOT_CONFIGURED`.
  - Background delivery is marked `FAILED` with an operator-visible reason; orders continue to work.

### Templates

- Email: HTML + plain text via Thymeleaf, escaped by default.
- SMS: short plain text under 320 chars.
- Push: title/body with order number, no PII beyond first name.
- Include:
  - order number, item summary, total, pickup window, maps link for ready
  - Venmo handle and amount for accepted
  - rejection/cancellation reason
  - menu link for published/reminder
- All links absolute using `APP_PUBLIC_BASE_URL`.
- No secrets in templates.

### Textbee webhook

- `POST /api/v1/webhooks/textbee`.
- Verify `X-Textbee-Signature` using `TEXTBEE_WEBHOOK_SECRET` (HMAC-SHA256 of raw body).
- STOP/STOPALL/UNSUBSCRIBE → set `sms_opt_out_at=now`.
- START/UNSTOP → clear opt-out only if prior consent exists.
- Always return 204 for valid signatures to avoid retries. Invalid signature → 401.
- Audit consent changes.

### Admin notifications

- New order:
  - Push to all active admin devices with FCM token.
  - If none succeed, email `ADMIN_ALERT_EMAIL`.
  - If push fails for a token, clear it.
- Order paid (bake-day paper trail):
  - On successful transition to `PAID`, send email to `ADMIN_ALERT_EMAIL` with full bake/fulfillment order details (see Payments).
  - This email is required regardless of admin FCM token state; delivery failures follow normal retry/backoff and appear in admin notification list for retry.
- Admin notification list:
  - `GET /admin/v1/notifications?status=`.
  - `POST /admin/v1/notifications/{id}/retry`.
- Delivery rows include masked destinations only. Admin-facing emails (new-order fallback, `ORDER_PAID`) may contain order/customer details necessary for operations; logs and API delivery metadata must still mask destinations and avoid unnecessary PII leakage.

## API operations

- `getNotificationPreferences`
- `updateNotificationPreferences`
- `updateOrderPayment`
- `notifyOrderDelayed` — handler writes `delay_notified_at`/`delay_reason` on `orders` in same transaction as outbox event; returns 202.
- `listNotifications`
- `retryNotification`
- `receiveTextbeeWebhook`
- `updateAdminPushToken` (implement token persistence here; the FCM columns arrive in V6)
- `sendMenuDateReminder`

## Ordered tasks

1. Add `V6__notifications.sql` and entities/repositories.
2. Implement payment update endpoint and Venmo instructions.
3. Implement preference defaults and read/update endpoints.
4. Implement outbox writer and order event handlers.
5. Implement template rendering.
6. Implement Mailgun, Textbee, and FCM senders plus local sender.
7. Implement dispatch scheduler, retry policy, and failure states.
8. Implement menu publication and reminder schedulers.
9. Implement admin new-order push/email fallback.
10. Implement Textbee STOP/START webhook.
11. Implement notification/admin retry endpoints.
12. Add audit events.
13. Add WireMock/fake-provider tests and scheduler tests.

## Acceptance criteria

- Given a pending order, when admin marks paid with `VENMO`, then status is `PAID`, `paid_at` set, audit recorded, and an admin `ORDER_PAID` email is queued/sent to `ADMIN_ALERT_EMAIL` containing full bake/fulfillment order details (order number, bake date, pickup window, customer snapshots, items/options/notes, totals, payment method, `paid_at`).
- Given a paid order, when admin corrects to unpaid, then status is `UNPAID`, audit recorded, and no `ORDER_PAID` email is sent.
- Given an order already marked paid, when the same paid update is retried/duplicated, then no duplicate `ORDER_PAID` admin email is sent (idempotent by `order:{orderId}:paid`).
- Given an accepted order, then the customer gets an email (local log) with Venmo handle and amount.
- Given a ready order, then the customer gets email/SMS (per preference) with the maps link.
- Given a customer without SMS consent, when preferences enable SMS, then 400 `SMS_CONSENT_REQUIRED`.
- Given a STOP webhook with a valid signature, then SMS is suppressed for that customer.
- Given an opted-out customer explicitly re-consents via `PUT`, then `sms_opt_out_at` is cleared and SMS is allowed again.
- Given an invalid Textbee signature, then 401 and no change.
- Given a menu with `notifyAt` due, when the scheduler runs, then opted-in customers get one `MENU_PUBLISHED` notification and `notify_sent_at` is set.
- Given a menu published twice, then the idempotency key prevents duplicate sends.
- Given the day before a bake date at 09:00, when the scheduler runs, then opted-in customers without a `PENDING`, `ACCEPTED`, `READY`, or `COMPLETED` order get one reminder, and customers with one do not.
- Given a failed provider call, then the delivery is retried with backoff and eventually `FAILED`.
- Given a failed notification, when admin retries, then it is queued again.
- Given a new order, then the admin gets FCM push; if no token succeeds, an email is sent.
- Given a rejected order, then the customer notification includes the reason.
- Given a menu date cancellation, then opted-in customers and active order customers are notified.

## Test plan

- Unit: template rendering, preference suppression, quiet hours, retry backoff, idempotency keys (including `order:{orderId}:paid`).
- Integration: payment marking/correction, audit, and `ORDER_PAID` admin email queued on paid transition with full bake/fulfillment details; no email on correction to unpaid; no duplicate on retry.
- Integration: preference defaults and SMS consent validation.
- Integration: order event → notification rows → provider call (WireMock).
- Integration: menu publish scheduler with fixed clock.
- Integration: reminder scheduler audience selection.
- Integration: STOP/START webhook signature and consent.
- Integration: FCM invalid token removal and new-order email fallback.
- Integration: admin retry for failed `ORDER_PAID` email.
- Security: no PII/secret leakage in logs; HTML escaping for email templates; admin email payload contains only necessary order/fulfillment data.

## Security checklist

- [ ] Provider API keys only in env.
- [ ] Webhook HMAC verified on raw body.
- [ ] SMS consent enforced server-side.
- [ ] STOP honored immediately.
- [ ] Email HTML escaped.
- [ ] Masked destinations in admin API/logs.
- [ ] Retry endpoints admin-only.
- [ ] Audit consent/payment changes.
- [ ] Rate limits on notification-triggering customer actions (S2/S5).

## Quality gate

This phase is not complete until `/phase-gate S6` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

All acceptance criteria pass, local profile logs all messages, provider integrations are testable with fakes, and W6/W7/A7/A8 can build against the contract.

## Handoff notes

- S7 configures real provider secrets and Cloudflare.
- A8 registers FCM tokens and handles push payloads.
- Notifications are at-least-once with idempotency; UI should tolerate duplicate delivery.
- Email/SMS delivery failure does not roll back orders or payments.
