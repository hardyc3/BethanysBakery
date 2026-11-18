-- S4: menus, bake dates, pickup windows, offerings, and customization snapshots

CREATE TABLE menu (
    id uuid PRIMARY KEY,
    title varchar(120) NOT NULL,
    notes text NULL,
    status varchar(16) NOT NULL,
    notification_enabled boolean NOT NULL,
    notify_at timestamptz NULL,
    notify_sent_at timestamptz NULL,
    published_at timestamptz NULL,
    cancelled_at timestamptz NULL,
    cancellation_reason text NULL,
    version int NOT NULL DEFAULT 0,
    created_by uuid NULL REFERENCES admin_user(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_menu_title CHECK (char_length(title) BETWEEN 1 AND 120),
    CONSTRAINT ck_menu_notes CHECK (notes IS NULL OR char_length(notes) <= 2000),
    CONSTRAINT ck_menu_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    CONSTRAINT ck_menu_cancellation_reason CHECK (cancellation_reason IS NULL OR char_length(cancellation_reason) <= 500)
);
CREATE INDEX idx_menu_status_created ON menu (status, created_at DESC);
CREATE INDEX idx_menu_published ON menu (published_at DESC);

CREATE TABLE menu_date (
    id uuid PRIMARY KEY,
    menu_id uuid NOT NULL REFERENCES menu(id) ON DELETE CASCADE,
    bake_date date NOT NULL,
    status varchar(16) NOT NULL,
    reminder_sent_at timestamptz NULL,
    cancelled_at timestamptz NULL,
    cancellation_reason text NULL,
    version int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_menu_date_bake UNIQUE (menu_id, bake_date),
    CONSTRAINT ck_menu_date_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    CONSTRAINT ck_menu_date_cancellation_reason CHECK (cancellation_reason IS NULL OR char_length(cancellation_reason) <= 500)
);
CREATE INDEX idx_menu_date_menu_order ON menu_date (menu_id, bake_date);
CREATE INDEX idx_menu_date_public ON menu_date (status, bake_date);

CREATE TABLE pickup_window (
    id uuid PRIMARY KEY,
    menu_date_id uuid NOT NULL REFERENCES menu_date(id) ON DELETE CASCADE,
    label varchar(60) NOT NULL,
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    max_orders int NOT NULL,
    reserved_orders int NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL,
    version int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_pickup_window_label CHECK (char_length(label) BETWEEN 1 AND 60),
    CONSTRAINT ck_pickup_window_max_orders CHECK (max_orders > 0),
    CONSTRAINT ck_pickup_window_reserved CHECK (reserved_orders >= 0),
    CONSTRAINT ck_pickup_window_range CHECK (end_at > start_at),
    CONSTRAINT ck_pickup_window_status CHECK (status IN ('ACTIVE', 'CANCELLED'))
);
CREATE INDEX idx_pickup_window_date ON pickup_window (menu_date_id, start_at);

CREATE TABLE offering (
    id uuid PRIMARY KEY,
    menu_date_id uuid NOT NULL REFERENCES menu_date(id) ON DELETE CASCADE,
    item_template_id uuid NOT NULL REFERENCES item_template(id),
    name varchar(120) NOT NULL,
    description text NULL,
    photo_media_id uuid NULL REFERENCES media(id),
    ingredients_text text NULL,
    allergen_tags jsonb NOT NULL DEFAULT '[]'::jsonb,
    price_cents int NOT NULL,
    quantity_limit int NOT NULL,
    reserved_count int NOT NULL DEFAULT 0,
    max_per_customer int NULL,
    status varchar(16) NOT NULL,
    version int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_offering_template_per_date UNIQUE (menu_date_id, item_template_id),
    CONSTRAINT ck_offering_name CHECK (char_length(name) BETWEEN 1 AND 120),
    CONSTRAINT ck_offering_description CHECK (description IS NULL OR char_length(description) <= 2000),
    CONSTRAINT ck_offering_ingredients CHECK (ingredients_text IS NULL OR char_length(ingredients_text) <= 4000),
    CONSTRAINT ck_offering_allergen_count CHECK (jsonb_array_length(allergen_tags) <= 20),
    CONSTRAINT ck_offering_price CHECK (price_cents BETWEEN 0 AND 99999),
    CONSTRAINT ck_offering_quantity CHECK (quantity_limit BETWEEN 1 AND 9999),
    CONSTRAINT ck_offering_reserved CHECK (reserved_count >= 0),
    CONSTRAINT ck_offering_max_per_customer CHECK (max_per_customer IS NULL
        OR (max_per_customer >= 1 AND max_per_customer <= quantity_limit)),
    CONSTRAINT ck_offering_status CHECK (status IN ('ACTIVE', 'CANCELLED'))
);
CREATE INDEX idx_offering_date_status ON offering (menu_date_id, status);
CREATE INDEX idx_offering_photo_media ON offering (photo_media_id);

CREATE TABLE offering_window (
    offering_id uuid NOT NULL REFERENCES offering(id) ON DELETE CASCADE,
    pickup_window_id uuid NOT NULL REFERENCES pickup_window(id),
    PRIMARY KEY (offering_id, pickup_window_id)
);
CREATE INDEX idx_offering_window_window ON offering_window (pickup_window_id);

CREATE TABLE offering_option_group (
    id uuid PRIMARY KEY,
    offering_id uuid NOT NULL REFERENCES offering(id) ON DELETE CASCADE,
    source_group_id uuid NULL,
    name varchar(80) NOT NULL,
    selection_type varchar(16) NOT NULL,
    required boolean NOT NULL,
    min_selections int NOT NULL,
    max_selections int NOT NULL,
    sort_order int NOT NULL,
    CONSTRAINT ck_offering_group_name CHECK (char_length(name) BETWEEN 1 AND 80),
    CONSTRAINT ck_offering_group_selection CHECK (selection_type IN ('SINGLE', 'MULTI')),
    CONSTRAINT ck_offering_group_min CHECK (min_selections >= 0),
    CONSTRAINT ck_offering_group_max CHECK (max_selections >= 1),
    CONSTRAINT ck_offering_group_range CHECK (min_selections <= max_selections),
    CONSTRAINT ck_offering_group_single CHECK (selection_type <> 'SINGLE' OR max_selections = 1)
);
CREATE INDEX idx_offering_group_order ON offering_option_group (offering_id, sort_order);

CREATE TABLE offering_option (
    id uuid PRIMARY KEY,
    group_id uuid NOT NULL REFERENCES offering_option_group(id) ON DELETE CASCADE,
    source_option_id uuid NULL,
    name varchar(80) NOT NULL,
    price_delta_cents int NOT NULL,
    available boolean NOT NULL,
    sort_order int NOT NULL,
    CONSTRAINT ck_offering_option_name CHECK (char_length(name) BETWEEN 1 AND 80),
    CONSTRAINT ck_offering_option_price CHECK (price_delta_cents >= 0)
);
CREATE UNIQUE INDEX uq_offering_option_group_name ON offering_option (group_id, lower(name));
CREATE INDEX idx_offering_option_order ON offering_option (group_id, sort_order);
