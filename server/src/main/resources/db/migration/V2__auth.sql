-- S2: auth, accounts, devices, sessions, audit

CREATE TABLE admin_user (
    id uuid PRIMARY KEY,
    email citext NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    password_hash text NOT NULL,
    totp_secret_encrypted text NOT NULL,
    totp_enabled boolean NOT NULL DEFAULT true,
    recovery_codes_hash jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(16) NOT NULL,
    failed_login_count int NOT NULL DEFAULT 0,
    locked_until timestamptz NULL,
    last_login_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_admin_user_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_admin_user_failed_login_count CHECK (failed_login_count >= 0)
);
CREATE UNIQUE INDEX uq_admin_user_single_active ON admin_user ((status)) WHERE status = 'ACTIVE';

CREATE TABLE admin_device (
    id uuid PRIMARY KEY,
    admin_user_id uuid NOT NULL REFERENCES admin_user(id),
    name varchar(80) NOT NULL,
    platform varchar(16) NOT NULL,
    public_key_pem text NOT NULL,
    cert_fingerprint_sha256 varchar(64) NOT NULL UNIQUE,
    api_key_hash text NOT NULL UNIQUE,
    status varchar(16) NOT NULL,
    paired_at timestamptz NOT NULL,
    last_seen_at timestamptz NULL,
    revoked_at timestamptz NULL,
    app_version varchar(64) NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_admin_device_platform CHECK (platform IN ('ANDROID')),
    CONSTRAINT ck_admin_device_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE TABLE device_pairing (
    id uuid PRIMARY KEY,
    code_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at timestamptz NULL,
    created_by varchar(64) NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE customer (
    id uuid PRIMARY KEY,
    name varchar(120) NULL,
    email citext NULL,
    phone_e164 varchar(32) NULL,
    email_verified_at timestamptz NULL,
    phone_verified_at timestamptz NULL,
    sms_consent_at timestamptz NULL,
    sms_opt_out_at timestamptz NULL,
    status varchar(16) NOT NULL,
    last_login_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_customer_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_customer_contact_present CHECK (email IS NOT NULL OR phone_e164 IS NOT NULL)
);
CREATE UNIQUE INDEX uq_customer_email ON customer (email) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX uq_customer_phone ON customer (phone_e164) WHERE phone_e164 IS NOT NULL;

CREATE TABLE otp_challenge (
    id uuid PRIMARY KEY,
    channel varchar(16) NOT NULL,
    purpose varchar(32) NOT NULL,
    destination_hash varchar(64) NOT NULL,
    destination_encrypted text NOT NULL,
    code_hash text NOT NULL,
    attempts int NOT NULL DEFAULT 0,
    max_attempts int NOT NULL DEFAULT 5,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_otp_channel CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT ck_otp_purpose CHECK (purpose IN ('LOGIN', 'CONTACT_VERIFY')),
    CONSTRAINT ck_otp_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_otp_max_attempts CHECK (max_attempts > 0)
);
CREATE INDEX idx_otp_destination_created ON otp_challenge (destination_hash, created_at);
CREATE INDEX idx_otp_expires_at ON otp_challenge (expires_at);

CREATE TABLE refresh_token (
    id uuid PRIMARY KEY,
    subject_type varchar(16) NOT NULL,
    subject_id uuid NOT NULL,
    device_id uuid NULL REFERENCES admin_device(id),
    token_hash varchar(64) NOT NULL UNIQUE,
    family_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    rotated_at timestamptz NULL,
    revoked_at timestamptz NULL,
    replaced_by uuid NULL,
    user_agent varchar(512) NULL,
    ip varchar(64) NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_refresh_subject_type CHECK (subject_type IN ('CUSTOMER', 'ADMIN'))
);
CREATE INDEX idx_refresh_subject ON refresh_token (subject_type, subject_id);
CREATE INDEX idx_refresh_family ON refresh_token (family_id);
CREATE INDEX idx_refresh_device ON refresh_token (device_id);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    actor_type varchar(16) NOT NULL,
    actor_id uuid NULL,
    action varchar(64) NOT NULL,
    entity_type varchar(64) NOT NULL,
    entity_id uuid NOT NULL,
    before_json jsonb NULL,
    after_json jsonb NULL,
    request_id varchar(128) NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_audit_actor_type CHECK (actor_type IN ('ADMIN', 'CUSTOMER', 'SYSTEM'))
);
CREATE INDEX idx_audit_entity ON audit_event (entity_type, entity_id);
CREATE INDEX idx_audit_created ON audit_event (created_at DESC);
