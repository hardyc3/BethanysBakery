# A7 — Admin Notification Controls and Queue

- Status: ready
- Depends on: A6, S6, S9
- Contract: `docs/api/openapi.yaml` → Admin Notifications (queue and recipient preview), menu/date publish and cancel
- Next: A8

## Goal

Give Bethany control over customer notifications and visibility into delivery failures: menu announcement scheduling, day-before reminders, delays, cancellations, rejections, and retry.

## Non-goals

- Push transport and FCM client (removed in S8; no A8 push client).
- Customer preference editing (W7).
- Marketing campaigns.
- Editing message templates in the app.

## Screens and controls

### Menu publish notification

In A3 publish flow (implemented here if not done):

- Toggle: send new-menu notification, default ON.
- Notification date/time: when untouched, send `notifyAt=null`/omit it so the server uses 4 days before first bake at 09:00 Denver; display the returned value. Edits send an explicit instant.
- Preview: event type, authoritative recipient count from `previewNotificationRecipients` ("X customers will be notified"), per-channel breakdown, "View recipients" action, date/time, and as-of note that the count can change before a scheduled send.
- On publish, show whether it is scheduled or sent immediately.
- No resend endpoint exists; to announce again, the admin must use a future spec. Explain in UI help text.

### Reminder

- On bake date detail / bake list header:
  - "Send day-before reminder now".
  - Confirmation with audience from `previewNotificationRecipients`: opted-in customers with no `PENDING`, `ACCEPTED`, `READY`, or `COMPLETED` order for that date; show count/channels and allow opening the masked recipient list.
  - Prevent duplicate sends; show if already sent.
  - 202 response → show queued state.

### Delay

- From order detail (A6):
  - optional reason.
  - preview: "Customer will be notified by their selected channels." plus server recipient count and "View recipients" for masked detail.
  - confirmation.
  - success shows `delayNotifiedAt` and reason.

### Rejection

- From order detail:
  - reason required.
  - preview with recipient count and masked recipient detail.
  - warning: inventory is released and the customer is notified.
  - cannot be undone.

### Menu/date cancellation

- From A3:
  - optional reason.
  - warning: active orders are cancelled, inventory released, customers notified; refunds manual.
  - recipient count and masked recipient list from `previewNotificationRecipients` before confirmation.
  - confirmation requires explicit acknowledgement.

### Shared recipient preview panel

- Opening: from any notification action in A3/A6/A7 via "View recipients".
- Data: `previewNotificationRecipients` with event type, entity type, and entity ID; paged lazily.
- Display: distinct customer count, EMAIL/SMS channel counts, as-of time, and paginated rows with name, masked email/phone, channels, and suppression reasons.
- Copy: "Based on current preferences; recipients may change before a scheduled send."
- Read-only: no mutation or retry from this panel.

### Notification queue

- Screen: Settings → Notifications.
- Filters: Failed, Queued, Sent, Suppressed.
- Rows:
  - event type
  - recipient type (customer/admin)
  - entity reference (order/menu/date link when possible)
  - channel statuses
  - attempts
  - last error (sanitized)
  - created/scheduled time
- Retry failed notification:
  - confirmation.
  - shows requeued state.
- Masked destinations only.
- Empty/error/loading states.
- Pull-to-refresh.

## Rules

- Admin cannot edit customer notification preferences.
- SMS suppression/consent is server-side; show "suppressed" with reason if available.
- No notification action should appear to succeed before server confirmation.
- Duplicate reminder sends are prevented by server idempotency; UI also disables after sent.
- Reasons are optional/required per endpoint; enforce.
- Times in America/Denver with timezone label.

## API integration

- `publishMenu` (notification fields)
- `sendMenuDateReminder`
- `notifyOrderDelayed`
- `rejectOrder`
- `cancelMenu`
- `cancelMenuDate`
- `previewNotificationRecipients`
- `listNotifications`
- `retryNotification`
- `getMenuDate`
- `getAdminSummary`

## Ordered tasks

1. Add publish notification settings/preview to A3 flow.
2. Add reminder action with audience confirmation and sent state.
3. Add delay flow review/preview.
4. Add rejection reason/preview.
5. Add cancellation warning copy.
6. Build the shared recipient preview panel from `previewNotificationRecipients`.
7. Build notification queue list/filters.
8. Implement retry with confirmation and refresh.
9. Handle suppressed/failed states and sanitized errors.
10. Add tests with MockWebServer and Compose UI.
11. Add help text for manual resend limits.

## Acceptance criteria

- Given a draft menu, then publish shows notification ON by default and the default date/time.
- Given notification disabled, then publish sends no menu notification.
- Given a scheduled notification, then the menu shows the scheduled state.
- Given a baked date, when sending a reminder, then the confirmation lists the audience rule, server recipient count/channels, masked recipient list, and the response is queued.
- Given a reminder already sent, then the action is disabled with an explanation.
- Given a delay with reason, then the customer notification is queued and the order shows delay info.
- Given a rejection without reason, then submit is blocked.
- Given a rejection, then a warning explains inventory release and notification.
- Given a date cancel, then the admin must acknowledge the consequences.
- Given failed notifications, then they appear with attempts and sanitized error.
- Given retry, then the notification is requeued and status refreshes.
- Given a suppressed SMS, then the reason is visible without exposing the destination.
- Given any notification action, when "View recipients" opens, then the panel shows read-only, paged, masked recipients with an as-of note and causes no state change.

## Test plan

- MockWebServer: publish with notification fields, reminder 202, delay, reject, cancel, list, retry, recipient preview success/empty/error.
- Compose UI: confirmations, validation, disabled states, filters, recipient count chip and paged preview panel.
- Error/409 handling.
- Accessibility: warnings, destructive dialogs, status text.
- Manual: verify local provider logs and admin queue.

## Security checklist

- [ ] Admin auth on all requests.
- [ ] Destructive actions confirmed.
- [ ] Reasons escaped.
- [ ] Masked destinations only.
- [ ] No notification retry for non-admin.
- [ ] No PII in logs.
- [ ] Server-side idempotency relied on.
- [ ] Recipient preview is read-only and shows masked destinations only.

## Quality gate

This phase is not complete until `/phase-gate A7` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

Bethany can intentionally send or suppress announcements, reminders, delays, rejections, and cancellations, and can see/retry delivery failures.

## Handoff notes

- S8 removes FCM admin push. New-order/admin alerts are Mailgun email to
  `ADMIN_ALERT_EMAIL` plus optional Textbee SMS to `ADMIN_ALERT_PHONE`; there is
  no push token registration or push payload handling.
- Admin access still uses mTLS device pairing (certificate + API key) over the
  one hostname. Deep links into the queue/order detail remain a client concern,
  not a push transport.
- Message copy lives in server templates; app shows previews only.
- If resend is added later, update OpenAPI and this spec together.
- A3 and A6 consume the recipient preview panel defined here; keep it shared instead of duplicating recipient-list UI.
