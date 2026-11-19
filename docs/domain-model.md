# Bethany's Bakery — Domain Model

Status: approved baseline  
Last updated: 2026-11-14

## 1. Conventions

- IDs: UUID v7 stored as PostgreSQL `uuid`.
- Money: integer cents, USD.
- Instants: `timestamptz`, UTC.
- Bake dates: `date`, interpreted in `America/Denver`.
- Enums: stored as `varchar` with check constraints, mapped to Java enums.
- Optimistic locking: `version` column on mutable aggregates.
- No soft delete. Status fields control visibility.
- Audit events are append-only.

## 2. Aggregates and tables

### 2.1 Identity

#### `admin_user`

Single admin account. Unique constraint on email.

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| email | citext | unique, normalized |
| name | varchar | display name (bootstrap CLI) |
| password_hash | text | Argon2id |
| totp_secret_encrypted | text | AES-GCM with app key |
| totp_enabled | boolean | true after bootstrap |
| recovery_codes_hash | jsonb | array of hashes |
| status | varchar | ACTIVE, DISABLED |
| failed_login_count | int | reset on success |
| locked_until | timestamptz | temporary lockout |
| last_login_at | timestamptz | |
| created_at, updated_at | timestamptz | |

#### `admin_device`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| admin_user_id | uuid | FK |
| name | varchar | device label |
| platform | varchar | ANDROID |
| public_key_pem | text | EC P-256 |
| cert_fingerprint_sha256 | varchar(64) | unique |
| api_key_hash | text | random 256-bit, hashed |
| status | varchar | ACTIVE, REVOKED |
| paired_at, last_seen_at, revoked_at | timestamptz | |
| fcm_token | text | added in S6 |
| app_version | varchar | last seen |
| created_at, updated_at | timestamptz | |

#### `device_pairing`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| code_hash | text | 128-bit code, hashed |
| expires_at | timestamptz | 15 minutes |
| used_at | timestamptz | single use |
| created_by | varchar | CLI or admin |
| created_at | timestamptz | |

#### `customer`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| name | varchar | required before ordering |
| email | citext | nullable, unique when present |
| phone_e164 | varchar | nullable, unique when present |
| email_verified_at | timestamptz | |
| phone_verified_at | timestamptz | |
| sms_consent_at | timestamptz | explicit opt-in |
| sms_opt_out_at | timestamptz | STOP |
| status | varchar | ACTIVE, DISABLED |
| last_login_at | timestamptz | |
| created_at, updated_at | timestamptz | |

At least one of email/phone is required. Login verifies the used channel. A customer can set the other channel later; it is unverified until verified by OTP.

#### `otp_challenge`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| channel | varchar | EMAIL, SMS |
| destination_hash | varchar(64) | normalized destination |
| destination_encrypted | text | normalized destination (LOGIN) or `customerId|destination` (CONTACT_VERIFY) |
| code_hash | text | salted hash |
| purpose | varchar | LOGIN, CONTACT_VERIFY |
| attempts | int | max 5 |
| expires_at | timestamptz | 10 minutes |
| consumed_at | timestamptz | |
| created_at | timestamptz | |

Indexes: destination hash + created_at, expires_at.

#### `refresh_token`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| subject_type | varchar | CUSTOMER, ADMIN |
| subject_id | uuid | |
| device_id | uuid | nullable, admin devices |
| token_hash | varchar(64) | unique |
| family_id | uuid | rotation family |
| expires_at | timestamptz | |
| rotated_at | timestamptz | |
| revoked_at | timestamptz | |
| replaced_by | uuid | |
| user_agent, ip | varchar | truncated |
| created_at | timestamptz | |

#### `audit_event`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| actor_type | varchar | ADMIN, CUSTOMER, SYSTEM |
| actor_id | uuid | nullable |
| action | varchar | e.g. ORDER_ACCEPTED |
| entity_type | varchar | |
| entity_id | uuid | |
| before_json | jsonb | redacted |
| after_json | jsonb | redacted |
| request_id | varchar | |
| created_at | timestamptz | append-only |

### 2.2 Catalog

#### `media`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| storage_key | varchar | path under configured media root (`MEDIA_ROOT`) |
| original_filename | varchar | |
| content_type | varchar | image/jpeg, image/png, image/webp |
| size_bytes | bigint | max 10 MB |
| width, height | int | original |
| variants | jsonb | keys: 400, 800, 1600 |
| sha256 | varchar(64) | |
| status | varchar | ACTIVE, DELETED |
| created_by | uuid | admin |
| created_at | timestamptz | |

#### `item_template`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| name | varchar | |
| description | text | |
| photo_media_id | uuid | FK media, nullable |
| ingredients_text | text | free text |
| allergen_tags | jsonb | string array |
| active | boolean | |
| version | int | |
| created_at, updated_at | timestamptz | |

#### `customization_group`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| item_template_id | uuid | FK |
| name | varchar | e.g. Toppings |
| selection_type | varchar | SINGLE, MULTI |
| required | boolean | |
| min_selections | int | |
| max_selections | int | |
| sort_order | int | |

Constraint: `0 <= min <= max`; `SINGLE` implies max 1.

#### `customization_option`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| group_id | uuid | FK |
| name | varchar | |
| price_delta_cents | int | may be 0 or positive |
| available | boolean | |
| sort_order | int | |

### 2.3 Menus and offerings

#### `menu`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| title | varchar | |
| notes | text | |
| status | varchar | DRAFT, PUBLISHED, CANCELLED |
| notification_enabled | boolean | default true |
| notify_at | timestamptz | default first bake date - 4 days at 09:00 |
| notify_sent_at | timestamptz | |
| published_at | timestamptz | |
| cancelled_at | timestamptz | |
| cancellation_reason | text | optional |
| version | int | |
| created_by | uuid | admin |
| created_at, updated_at | timestamptz | |

#### `menu_date`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| menu_id | uuid | FK |
| bake_date | date | unique per menu |
| status | varchar | DRAFT, PUBLISHED, CANCELLED |
| reminder_sent_at | timestamptz | 09:00 day before |
| cancelled_at | timestamptz | |
| cancellation_reason | text | optional |
| version | int | |
| created_at, updated_at | timestamptz | |

Unique: `(menu_id, bake_date)`.

#### `pickup_window`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| menu_date_id | uuid | FK |
| label | varchar | e.g. "2:00 PM" |
| start_at | timestamptz | must be on bake_date, Denver |
| end_at | timestamptz | after start |
| max_orders | int | total orders in window |
| reserved_orders | int | default 0 |
| status | varchar | ACTIVE, CANCELLED |
| version | int | |
| created_at, updated_at | timestamptz | |

Constraint: `reserved_orders >= 0`, `max_orders > 0`.

Windows have no standalone cancel endpoint. Cancelling the parent menu date sets its windows and offerings to `CANCELLED`. Unused draft windows may be hard-deleted.

#### `offering`

One item template offered on one bake date.

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| menu_date_id | uuid | FK |
| item_template_id | uuid | FK |
| name | varchar | snapshot |
| description | text | snapshot |
| photo_media_id | uuid | snapshot, nullable |
| ingredients_text | text | snapshot |
| allergen_tags | jsonb | snapshot |
| price_cents | int | per item |
| quantity_limit | int | total for date |
| reserved_count | int | default 0 |
| max_per_customer | int | nullable; per bake date |
| status | varchar | ACTIVE, CANCELLED |
| version | int | |
| created_at, updated_at | timestamptz | |

Unique: `(menu_date_id, item_template_id)`.  
Availability = `max(0, quantity_limit - reserved_count)`.

#### `offering_window`

Join table for offering ↔ pickup window. An offering may be available in one or more windows.

| Field | Type |
|---|---|
| offering_id | uuid |
| pickup_window_id | uuid |

PK: `(offering_id, pickup_window_id)`.

#### `offering_option_group`

Snapshot of a customization group at offering creation, editable independently of the template.

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| offering_id | uuid | FK |
| name | varchar | |
| selection_type | varchar | SINGLE, MULTI |
| required | boolean | |
| min_selections | int | |
| max_selections | int | |
| sort_order | int | |
| source_group_id | uuid | nullable |

#### `offering_option`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| group_id | uuid | FK |
| name | varchar | |
| price_delta_cents | int | |
| available | boolean | |
| sort_order | int | |
| source_option_id | uuid | nullable |

### 2.4 Orders

#### `orders`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| order_number | varchar | unique, `BB-yyyyMMdd-0001` |
| customer_id | uuid | FK |
| menu_date_id | uuid | FK |
| pickup_window_id | uuid | FK |
| idempotency_key | varchar | nullable, unique when present; prevents duplicate order creation on retries |
| status | varchar | PENDING, ACCEPTED, READY, COMPLETED, REJECTED, CANCELLED, EXPIRED |
| payment_status | varchar | UNPAID, PAID |
| payment_method | varchar | nullable: VENMO, CASH, OTHER |
| subtotal_cents | int | |
| total_cents | int | |
| customer_name | varchar | snapshot |
| customer_email | citext | snapshot |
| customer_phone | varchar | snapshot |
| notes | text | customer notes |
| rejection_reason | text | |
| cancellation_reason | text | |
| cancelled_by | varchar | CUSTOMER, ADMIN, SYSTEM |
| accepted_at, ready_at, completed_at, paid_at | timestamptz | |
| delay_notified_at | timestamptz | |
| delay_reason | text | |
| expires_at | timestamptz | cutoff |
| inventory_released_at | timestamptz | nullable; idempotent release marker |
| version | int | |
| created_at, updated_at | timestamptz | |

#### `order_line`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| order_id | uuid | FK |
| offering_id | uuid | FK |
| item_name | varchar | snapshot |
| unit_price_cents | int | base price |
| quantity | int | 1..99 |
| line_total_cents | int | computed with options |
| notes | text | |

#### `order_line_option`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| order_line_id | uuid | FK |
| group_name | varchar | snapshot |
| option_name | varchar | snapshot |
| price_delta_cents | int | snapshot |

One row per selected option. Price delta applies once per unit. `line_total = (unit_price + sum(deltas)) * quantity`.

#### `daily_order_sequence`

| Field | Type | Notes |
|---|---|---|
| bake_date | date | PK |
| next_value | int | |

Used to generate order numbers under a row lock.

### 2.5 Notifications

#### `notification_preference`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| customer_id | uuid | FK |
| event_type | varchar | see list |
| email_enabled | boolean | |
| sms_enabled | boolean | requires customer consent |
| updated_at | timestamptz | |

Unique: `(customer_id, event_type)`.

Event types:

- `MENU_PUBLISHED`
- `ORDER_REMINDER`
- `ORDER_ACCEPTED`
- `ORDER_READY`
- `ORDER_DELAYED`
- `ORDER_REJECTED`
- `ORDER_CANCELLED`
- `ORDER_EXPIRED`
- `MENU_CANCELLED`

Defaults on first read:

| Event | Email | SMS |
|---|---|---|
| MENU_PUBLISHED | on | off |
| ORDER_REMINDER | on | off |
| ORDER_ACCEPTED | on | on if consent |
| ORDER_READY | on | on if consent |
| ORDER_DELAYED | on | on if consent |
| ORDER_REJECTED | on | on if consent |
| ORDER_CANCELLED | on | on if consent |
| ORDER_EXPIRED | on | off |
| MENU_CANCELLED | on | on if consent |

SMS is suppressed unless `sms_consent_at` is set and `sms_opt_out_at` is null.

#### `notification`

One logical message to one recipient.

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| recipient_type | varchar | CUSTOMER, ADMIN |
| customer_id | uuid | nullable |
| admin_user_id | uuid | nullable |
| event_type | varchar | |
| entity_type | varchar | ORDER, MENU, MENU_DATE |
| entity_id | uuid | |
| idempotency_key | varchar | unique |
| payload | jsonb | template data |
| status | varchar | QUEUED, SENDING, SENT, FAILED, SUPPRESSED |
| created_at, scheduled_at, sent_at | timestamptz | |
| last_error | text | |
| attempts | int | |

#### `notification_delivery`

One channel attempt.

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| notification_id | uuid | FK |
| channel | varchar | EMAIL, SMS, PUSH |
| destination | varchar | redacted in logs |
| status | varchar | QUEUED, SENT, FAILED, SUPPRESSED |
| provider_message_id | varchar | |
| attempts | int | |
| last_error | text | |
| sent_at | timestamptz | |

#### `outbox_event`

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK |
| aggregate_type | varchar | |
| aggregate_id | uuid | |
| event_type | varchar | |
| payload | jsonb | |
| status | varchar | PENDING, PROCESSED, FAILED |
| attempts | int | |
| available_at | timestamptz | |
| processed_at | timestamptz | |
| last_error | text | |
| idempotency_key | varchar | nullable; logical-message dedupe key, unique when present |
| created_at | timestamptz | |

Scheduler polls due `PENDING` rows. Created in `V5__orders.sql` (S5) so the order transaction can enqueue `OrderCreated`; S6 reuses it and must not re-create it.

## 3. Relationships

```text
admin_user 1─* admin_device
customer   1─* refresh_token
customer   1─* otp_challenge (by destination)
customer   1─* orders
menu       1─* menu_date
menu_date  1─* pickup_window
menu_date  1─* offering
offering   *─* pickup_window (offering_window)
offering   1─* offering_option_group 1─* offering_option
orders     1─* order_line 1─* order_line_option
item_template 1─* customization_group 1─* customization_option
item_template 1─* offering
customer   1─* notification_preference
customer   1─* notification 1─* notification_delivery
```

## 4. State machines

### 4.1 Order status

```text
PENDING ──accept──▶ ACCEPTED ──ready──▶ READY ──complete──▶ COMPLETED
   │                    │                  │
   │                    └──cancel──────────┴──▶ CANCELLED
   ├──reject──▶ REJECTED
   ├──cancel──▶ CANCELLED
   └──expire──▶ EXPIRED
```

Rules:

- `PENDING`: holds inventory. Admin may accept/reject. Customer may cancel. Expires at cutoff.
- `ACCEPTED`: holds inventory. Admin may ready/cancel. Customer may cancel until bake day start. Payment may be marked any time.
- `READY`: holds inventory. Admin may complete/cancel. Customer cannot cancel.
- `COMPLETED`: terminal. Payment may still be corrected.
- `REJECTED`, `CANCELLED`, `EXPIRED`: terminal. Inventory released once.
- Admin cannot accept after cutoff.
- Payment status is independent of fulfillment status.

### 4.2 Payment status

```text
UNPAID ──mark paid──▶ PAID
PAID   ──correction──▶ UNPAID
```

Every transition writes an audit event.

### 4.3 Menu status

```text
DRAFT ──publish──▶ PUBLISHED ──cancel──▶ CANCELLED
```

Publishing publishes all non-cancelled dates.

### 4.4 Menu date status

```text
DRAFT ──publish──▶ PUBLISHED ──cancel──▶ CANCELLED
```

Cancelling a date cancels all active orders on that date and releases inventory.

## 5. Capacity invariants

1. `0 <= offering.reserved_count <= offering.quantity_limit` normally. Admin may lower `quantity_limit` below `reserved_count`; new orders fail until `reserved_count` drops below the limit.
2. `0 <= pickup_window.reserved_orders <= pickup_window.max_orders` normally. Same lowering rule applies.
3. `order.total_cents = sum(order_line.line_total_cents)`.
4. Every active order (`PENDING`, `ACCEPTED`, `READY`) contributes to exactly one window and to each offering line.
5. Terminal orders contribute nothing.
6. Inventory release is idempotent. Each order releases at most once.
7. Offering must be assigned to the order's pickup window.
8. Order line quantity is positive and does not exceed the offering's remaining quantity at reservation time.
9. Per-customer limit is checked across active orders for the same bake date and offering.
10. One order belongs to one bake date and one pickup window.

## 6. Time rules

- Timezone: `America/Denver`.
- Bake date `D` runs from `D 00:00` to `D 23:59:59` Denver time.
- Pickup window start/end are instants on `D`.
- Cutoff for a window: `start_at - 12 hours`.
- An order may be placed only before its window cutoff.
- Pending orders expire at cutoff.
- Customer cancellation allowed until `D 00:00` Denver time.
- Menu notification default: `first_bake_date - 4 days` at `09:00` Denver.
- Day-before reminder: `D - 1 day` at `09:00` Denver.
- DST handled by `America/Denver` zone rules. Instants are never stored as local strings.

## 7. Snapshot rules

Snapshot at offering creation from the item template:

- name, description, photo, ingredients, allergens
- customization groups/options and price deltas

Snapshot at order creation:

- customer name, email, phone
- item name, unit price
- selected group/option names and deltas
- order totals

Template edits never change published offerings. Offering edits never change existing orders.

## 8. Notification event rules

| Event | Recipient | Trigger | Channels |
|---|---|---|---|
| `MENU_PUBLISHED` | Opted-in customers | Menu notification time | Email, SMS if consented |
| `ORDER_REMINDER` | Opted-in customers with no `PENDING`, `ACCEPTED`, `READY`, or `COMPLETED` order on that date | 09:00 day before | Email, SMS if consented |
| `ORDER_ACCEPTED` | Order customer | Admin accepts | Email, SMS if consented |
| `ORDER_READY` | Order customer | Admin marks ready | Email, SMS if consented |
| `ORDER_DELAYED` | Order customer | Admin sends delay | Email, SMS if consented |
| `ORDER_REJECTED` | Order customer | Admin rejects, reason required | Email, SMS if consented |
| `ORDER_CANCELLED` | Order customer | Customer/admin/system cancels | Email, SMS if consented |
| `ORDER_EXPIRED` | Order customer | Cutoff expiry | Email |
| `MENU_CANCELLED` | Opted-in customers + active order customers | Menu/date cancel | Email, SMS if consented |
| `ORDER_PAID` (admin bake trail) | Admin (`ADMIN_ALERT_EMAIL`) | Order payment marked `PAID` | Email with full bake/fulfillment order details; durable paper trail |
| Admin new order | Admin | Order created | FCM + email fallback |

Idempotency keys:

- Menu publish: `menu:{menuId}:published`
- Reminder: `menu-date:{menuDateId}:reminder:{customerId}`
- Order event: `order:{orderId}:{eventType}`
- Admin paid order: `order:{orderId}:paid`
- Admin new order: `order:{orderId}:admin-new`
- Expiry: `order:{orderId}:expired`

## 9. Authorization matrix

| Action | Customer | Admin |
|---|---|---|
| Read published menu | yes | yes |
| Create order | own account | no |
| Read order | own orders only | all |
| Cancel order | own, before bake day, PENDING/ACCEPTED | any active, reason |
| Accept/reject order | no | PENDING only |
| Mark ready/complete | no | ACCEPTED/READY |
| Mark paid | no | any active or completed |
| Create/edit/publish menu | no | yes |
| Edit item templates | no | yes |
| Upload media | no | yes |
| Send notifications | automatic | yes |
| View bake list | no | yes |
| View audit log | no | yes |
| Manage devices | no | yes, via pair/revoke |

## 10. Index and constraint checklist

- `orders(customer_id, created_at desc)`
- `orders(menu_date_id, status, created_at)`
- `orders(pickup_window_id, status)`
- `orders(status, expires_at)` for expiry scan
- `order_line(offering_id)`
- `offering(menu_date_id, status)`
- `pickup_window(menu_date_id)`
- `offering_window(pickup_window_id)`
- `notification(status, scheduled_at)`
- `notification_delivery(notification_id)`
- `outbox_event(status, available_at)`
- `outbox_event(idempotency_key)` partial unique where not null (V5)
- `otp_challenge(destination_hash, created_at)`
- `refresh_token(token_hash)` unique
- `admin_device(cert_fingerprint_sha256)` unique
- `admin_device(api_key_hash)` unique
- Partial unique indexes for non-null customer email and phone
- Check constraints for enums, positive quantities, non-negative counters
