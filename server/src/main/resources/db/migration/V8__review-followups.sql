-- S8: diagnostic submission tracking and the single-active-admin-device invariant.

ALTER TABLE orders ADD COLUMN client_submitted_at timestamptz NULL;
ALTER TABLE orders ADD COLUMN request_fingerprint varchar(64) NULL;

DO $$
DECLARE active_device_count integer;
BEGIN
    SELECT count(*) INTO active_device_count FROM admin_device WHERE status = 'ACTIVE';
    IF active_device_count > 1 THEN
        RAISE EXCEPTION 'V8 blocked: % active admin devices found. Revoke extra active devices before deploy.',
            active_device_count;
    END IF;
END
$$;

CREATE UNIQUE INDEX uq_admin_device_single_active ON admin_device ((status)) WHERE status = 'ACTIVE';
