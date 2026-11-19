-- S6: notification preferences, notifications, and per-channel delivery attempts.
-- outbox_event is created by V5 (S5) and reused here. Do not recreate it or its index.

ALTER TABLE admin_device
    ADD COLUMN fcm_token text NULL,
    ADD COLUMN fcm_updated_at timestamptz NULL;

CREATE TABLE notification_preference (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    event_type varchar(40) NOT NULL,
    email_enabled boolean NOT NULL,
    sms_enabled boolean NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_notification_preference UNIQUE (customer_id, event_type),
    CONSTRAINT ck_notification_preference_event CHECK (event_type IN (
        'MENU_PUBLISHED', 'ORDER_REMINDER', 'ORDER_ACCEPTED', 'ORDER_READY', 'ORDER_DELAYED',
        'ORDER_REJECTED', 'ORDER_CANCELLED', 'ORDER_EXPIRED', 'MENU_CANCELLED'))
);

CREATE TABLE notification (
    id uuid PRIMARY KEY,
    recipient_type varchar(16) NOT NULL,
    customer_id uuid NULL REFERENCES customer(id),
    admin_user_id uuid NULL REFERENCES admin_user(id),
    event_type varchar(60) NOT NULL,
    entity_type varchar(40) NOT NULL,
    entity_id uuid NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(16) NOT NULL,
    attempts int NOT NULL DEFAULT 0,
    last_error text NULL,
    created_at timestamptz NOT NULL,
    scheduled_at timestamptz NOT NULL,
    sent_at timestamptz NULL,
    CONSTRAINT ck_notification_recipient CHECK (recipient_type IN ('CUSTOMER', 'ADMIN')),
    CONSTRAINT ck_notification_status CHECK (status IN ('QUEUED', 'SENDING', 'SENT', 'FAILED', 'SUPPRESSED')),
    CONSTRAINT ck_notification_attempts CHECK (attempts >= 0),
    CONSTRAINT uq_notification_idempotency UNIQUE (idempotency_key)
);
CREATE INDEX idx_notification_status_scheduled ON notification(status, scheduled_at);

CREATE TABLE notification_delivery (
    id uuid PRIMARY KEY,
    notification_id uuid NOT NULL REFERENCES notification(id) ON DELETE CASCADE,
    channel varchar(8) NOT NULL,
    destination varchar(320) NOT NULL,
    status varchar(16) NOT NULL,
    provider_message_id varchar(160) NULL,
    attempts int NOT NULL DEFAULT 0,
    last_error text NULL,
    sent_at timestamptz NULL,
    CONSTRAINT ck_notification_delivery_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),
    CONSTRAINT ck_notification_delivery_status CHECK (status IN ('QUEUED', 'SENDING', 'SENT', 'FAILED', 'SUPPRESSED')),
    CONSTRAINT ck_notification_delivery_attempts CHECK (attempts >= 0)
);
CREATE INDEX idx_notification_delivery_notification ON notification_delivery(notification_id);
