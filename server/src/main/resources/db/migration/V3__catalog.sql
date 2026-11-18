-- S3: catalog templates, customizations, and media

CREATE TABLE media (
    id uuid PRIMARY KEY,
    storage_key varchar(512) NOT NULL UNIQUE,
    original_filename varchar(512) NOT NULL,
    content_type varchar(32) NOT NULL,
    size_bytes bigint NOT NULL,
    width int NOT NULL,
    height int NOT NULL,
    variants jsonb NOT NULL DEFAULT '{}'::jsonb,
    sha256 varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    created_by uuid NULL REFERENCES admin_user(id),
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_media_content_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_media_status CHECK (status IN ('ACTIVE', 'DELETED')),
    CONSTRAINT ck_media_size_bytes CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    CONSTRAINT ck_media_dimensions CHECK (width > 0 AND height > 0),
    CONSTRAINT ck_media_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_media_status_created ON media (status, created_at DESC);
CREATE INDEX idx_media_sha256 ON media (sha256);

CREATE TABLE item_template (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    description text NULL,
    photo_media_id uuid NULL REFERENCES media(id),
    ingredients_text text NULL,
    allergen_tags jsonb NOT NULL DEFAULT '[]'::jsonb,
    active boolean NOT NULL,
    version int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_item_template_name CHECK (char_length(name) BETWEEN 1 AND 120),
    CONSTRAINT ck_item_template_description CHECK (description IS NULL OR char_length(description) <= 2000),
    CONSTRAINT ck_item_template_ingredients CHECK (ingredients_text IS NULL OR char_length(ingredients_text) <= 4000),
    CONSTRAINT ck_item_template_allergen_count CHECK (jsonb_array_length(allergen_tags) <= 20)
);
CREATE INDEX idx_item_template_active_name ON item_template (active, name);
CREATE INDEX idx_item_template_photo_media ON item_template (photo_media_id);

CREATE TABLE customization_group (
    id uuid PRIMARY KEY,
    item_template_id uuid NOT NULL REFERENCES item_template(id) ON DELETE CASCADE,
    name varchar(80) NOT NULL,
    selection_type varchar(16) NOT NULL,
    required boolean NOT NULL,
    min_selections int NOT NULL,
    max_selections int NOT NULL,
    sort_order int NOT NULL,
    CONSTRAINT ck_customization_group_name CHECK (char_length(name) BETWEEN 1 AND 80),
    CONSTRAINT ck_customization_group_selection_type CHECK (selection_type IN ('SINGLE', 'MULTI')),
    CONSTRAINT ck_customization_group_min_selections CHECK (min_selections >= 0),
    CONSTRAINT ck_customization_group_max_selections CHECK (max_selections >= 1),
    CONSTRAINT ck_customization_group_range CHECK (min_selections <= max_selections),
    CONSTRAINT ck_customization_group_single CHECK (selection_type <> 'SINGLE' OR max_selections = 1)
);
CREATE INDEX idx_customization_group_template_order ON customization_group (item_template_id, sort_order);

CREATE TABLE customization_option (
    id uuid PRIMARY KEY,
    group_id uuid NOT NULL REFERENCES customization_group(id) ON DELETE CASCADE,
    name varchar(80) NOT NULL,
    price_delta_cents int NOT NULL,
    available boolean NOT NULL,
    sort_order int NOT NULL,
    CONSTRAINT ck_customization_option_name CHECK (char_length(name) BETWEEN 1 AND 80),
    CONSTRAINT ck_customization_option_price CHECK (price_delta_cents >= 0)
);
CREATE UNIQUE INDEX uq_customization_option_group_name ON customization_option (group_id, lower(name));
CREATE INDEX idx_customization_option_group_order ON customization_option (group_id, sort_order);
