-- S5: orders, order lines, order number sequence, and the transactional outbox used by S5/S6.

CREATE TABLE orders (
    id uuid PRIMARY KEY,
    order_number varchar(32) NOT NULL,
    customer_id uuid NOT NULL REFERENCES customer(id),
    menu_date_id uuid NOT NULL REFERENCES menu_date(id),
    pickup_window_id uuid NOT NULL REFERENCES pickup_window(id),
    idempotency_key varchar(128) NULL,
    status varchar(16) NOT NULL,
    payment_status varchar(16) NOT NULL,
    payment_method varchar(16) NULL,
    subtotal_cents int NOT NULL,
    total_cents int NOT NULL,
    customer_name varchar(120) NOT NULL,
    customer_email citext NULL,
    customer_phone varchar(32) NULL,
    notes text NULL,
    rejection_reason text NULL,
    cancellation_reason text NULL,
    cancelled_by varchar(16) NULL,
    accepted_at timestamptz NULL,
    ready_at timestamptz NULL,
    completed_at timestamptz NULL,
    paid_at timestamptz NULL,
    delay_notified_at timestamptz NULL,
    delay_reason text NULL,
    expires_at timestamptz NOT NULL,
    inventory_released_at timestamptz NULL,
    version int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_orders_order_number UNIQUE (order_number),
    CONSTRAINT ck_orders_status CHECK (status IN
        ('PENDING', 'ACCEPTED', 'READY', 'COMPLETED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_orders_payment_status CHECK (payment_status IN ('UNPAID', 'PAID')),
    CONSTRAINT ck_orders_payment_method CHECK (payment_method IS NULL
        OR payment_method IN ('VENMO', 'CASH', 'OTHER')),
    CONSTRAINT ck_orders_cancelled_by CHECK (cancelled_by IS NULL
        OR cancelled_by IN ('CUSTOMER', 'ADMIN', 'SYSTEM')),
    CONSTRAINT ck_orders_customer_name CHECK (char_length(customer_name) BETWEEN 1 AND 120),
    CONSTRAINT ck_orders_totals CHECK (subtotal_cents >= 0 AND total_cents >= 0
        AND subtotal_cents = total_cents),
    CONSTRAINT ck_orders_notes CHECK (notes IS NULL OR char_length(notes) <= 500),
    CONSTRAINT ck_orders_rejection_reason CHECK (rejection_reason IS NULL OR char_length(rejection_reason) <= 500),
    CONSTRAINT ck_orders_cancellation_reason CHECK (cancellation_reason IS NULL OR char_length(cancellation_reason) <= 500),
    CONSTRAINT ck_orders_delay_reason CHECK (delay_reason IS NULL OR char_length(delay_reason) <= 500),
    CONSTRAINT ck_orders_idempotency_key CHECK (idempotency_key IS NULL OR char_length(idempotency_key) BETWEEN 1 AND 128)
);
CREATE UNIQUE INDEX uq_orders_idempotency ON orders (customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_orders_customer_created ON orders (customer_id, created_at DESC);
CREATE INDEX idx_orders_date_status_created ON orders (menu_date_id, status, created_at);
CREATE INDEX idx_orders_window_status ON orders (pickup_window_id, status);
CREATE INDEX idx_orders_status_expires ON orders (status, expires_at);

CREATE TABLE order_line (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    offering_id uuid NOT NULL REFERENCES offering(id),
    item_name varchar(120) NOT NULL,
    unit_price_cents int NOT NULL,
    quantity int NOT NULL,
    line_total_cents int NOT NULL,
    notes text NULL,
    CONSTRAINT ck_order_line_quantity CHECK (quantity BETWEEN 1 AND 99),
    CONSTRAINT ck_order_line_price CHECK (unit_price_cents >= 0),
    CONSTRAINT ck_order_line_total CHECK (line_total_cents >= 0),
    CONSTRAINT ck_order_line_notes CHECK (notes IS NULL OR char_length(notes) <= 300)
);
CREATE INDEX idx_order_line_offering ON order_line (offering_id);
CREATE INDEX idx_order_line_order ON order_line (order_id);

CREATE TABLE order_line_option (
    id uuid PRIMARY KEY,
    order_line_id uuid NOT NULL REFERENCES order_line(id) ON DELETE CASCADE,
    group_name varchar(80) NOT NULL,
    option_name varchar(80) NOT NULL,
    price_delta_cents int NOT NULL,
    CONSTRAINT ck_order_line_option_price CHECK (price_delta_cents >= 0)
);
CREATE INDEX idx_order_line_option_line ON order_line_option (order_line_id);

CREATE TABLE daily_order_sequence (
    bake_date date PRIMARY KEY,
    next_value int NOT NULL,
    CONSTRAINT ck_daily_order_sequence_next CHECK (next_value >= 1)
);

-- S5 writes OrderCreated here in the order transaction; S6 consumes it. Created in V5 because
-- the order transaction must be able to enqueue the event before notifications ship.
CREATE TABLE outbox_event (
    id uuid PRIMARY KEY,
    aggregate_type varchar(40) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(80) NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(16) NOT NULL,
    attempts int NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL,
    processed_at timestamptz NULL,
    last_error text NULL,
    idempotency_key varchar(160) NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);
CREATE UNIQUE INDEX uq_outbox_idempotency ON outbox_event (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_outbox_status_available ON outbox_event (status, available_at);
