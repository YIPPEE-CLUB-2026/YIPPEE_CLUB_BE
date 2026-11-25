-- Kept to SQL that MySQL 8 and H2 (MySQL mode) both accept. Times are UTC.

CREATE TABLE users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    email           VARCHAR(254)  NOT NULL,
    password_hash   VARCHAR(255)  NOT NULL,
    name            VARCHAR(100)  NOT NULL DEFAULT '',
    nickname        VARCHAR(50)   NOT NULL DEFAULT '',
    phone           VARCHAR(20)   NOT NULL DEFAULT '',
    agreements      VARCHAR(4000) NOT NULL,
    active_child_id BIGINT        NULL,
    token_version   INT           NOT NULL DEFAULT 0,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    last_login_at   DATETIME(6)   NULL,
    created_at      DATETIME(6)   NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE refresh_sessions (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    token_digest VARCHAR(64) NOT NULL,
    expires_at   DATETIME(6) NOT NULL,
    revoked_at   DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL,
    CONSTRAINT uq_refresh_sessions_digest UNIQUE (token_digest),
    CONSTRAINT fk_refresh_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE email_verifications (
    id               VARCHAR(80)  PRIMARY KEY,
    email            VARCHAR(254) NOT NULL,
    purpose          VARCHAR(32)  NOT NULL,
    code_digest      VARCHAR(64)  NOT NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    expires_at       DATETIME(6)  NOT NULL,
    verified_at      DATETIME(6)  NULL,
    token_digest     VARCHAR(64)  NULL,
    token_expires_at DATETIME(6)  NULL,
    consumed_at      DATETIME(6)  NULL,
    created_at       DATETIME(6)  NOT NULL,
    CONSTRAINT uq_email_verifications_token UNIQUE (token_digest)
);
CREATE INDEX ix_email_verifications_email ON email_verifications (email);

CREATE TABLE auth_rate_limits (
    key_digest   VARCHAR(64) PRIMARY KEY,
    window_start BIGINT      NOT NULL, -- epoch seconds
    hits         INT         NOT NULL
);

CREATE TABLE categories (
    id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE children (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    birth_date DATE         NOT NULL,
    gender     VARCHAR(20)  NOT NULL DEFAULT '',
    CONSTRAINT fk_children_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Deleting the chosen child clears the choice; it never deletes the parent.
ALTER TABLE users ADD CONSTRAINT fk_users_active_child
    FOREIGN KEY (active_child_id) REFERENCES children (id) ON DELETE SET NULL;

CREATE TABLE child_interests (
    child_id    BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    PRIMARY KEY (child_id, category_id),
    CONSTRAINT fk_child_interests_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE,
    CONSTRAINT fk_child_interests_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

CREATE TABLE activities (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    title          VARCHAR(255)  NOT NULL,
    description    VARCHAR(4000) NOT NULL,
    age_min_months INT           NOT NULL,
    age_max_months INT           NOT NULL,
    difficulty     VARCHAR(16)   NOT NULL,
    duration_min   INT           NOT NULL,
    duration_max   INT           NOT NULL,
    price          INT           NOT NULL DEFAULT 0,
    -- The price of 7 days of access. Null: sold for good only.
    rental_price   INT           NULL,
    image_path     VARCHAR(255)  NOT NULL,
    pdf_path       VARCHAR(255)  NOT NULL,
    is_public      BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    CONSTRAINT ck_activities_price CHECK (price >= 0),
    CONSTRAINT ck_activities_rental CHECK (rental_price IS NULL OR (rental_price > 0 AND rental_price < price)),
    CONSTRAINT ck_activities_age CHECK (age_min_months >= 0 AND age_max_months >= age_min_months),
    CONSTRAINT ck_activities_duration CHECK (duration_min >= 1 AND duration_max >= duration_min),
    CONSTRAINT ck_activities_difficulty CHECK (difficulty IN ('easy', 'hard'))
);

CREATE TABLE activity_categories (
    activity_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    PRIMARY KEY (activity_id, category_id),
    CONSTRAINT fk_activity_categories_activity FOREIGN KEY (activity_id) REFERENCES activities (id) ON DELETE CASCADE,
    CONSTRAINT fk_activity_categories_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

CREATE TABLE favorites (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    CONSTRAINT uq_favorites_user_activity UNIQUE (user_id, activity_id),
    CONSTRAINT fk_favorites_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_favorites_activity FOREIGN KEY (activity_id) REFERENCES activities (id) ON DELETE CASCADE
);

-- Money records. They never disappear with a user or an activity (no cascade).
CREATE TABLE orders (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id            BIGINT       NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    amount             INT          NOT NULL,
    pg_order_id        VARCHAR(64)  NOT NULL,
    pg_payment_key     VARCHAR(200) NULL,
    receipt_url        VARCHAR(500) NULL,
    confirm_started_at DATETIME(6)  NULL,
    paid_at            DATETIME(6)  NULL,
    -- When the buyer ticked the notice that a refund is limited once the file is downloaded. Null on a free order.
    refund_notice_agreed_at DATETIME(6) NULL,
    created_at         DATETIME(6)  NOT NULL,
    CONSTRAINT uq_orders_pg_order UNIQUE (pg_order_id),
    CONSTRAINT uq_orders_pg_payment UNIQUE (pg_payment_key),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_orders_status CHECK (status IN ('pending', 'paid', 'payment_failed', 'cancelled', 'refunded', 'review')),
    CONSTRAINT ck_orders_amount CHECK (amount >= 0)
);
CREATE INDEX ix_orders_user ON orders (user_id, id);
CREATE INDEX ix_orders_unsettled ON orders (status, confirm_started_at);

-- What was bought, at the price and under the title of that day.
CREATE TABLE order_items (
    order_id    BIGINT       NOT NULL,
    activity_id BIGINT       NOT NULL,
    title       VARCHAR(255) NOT NULL,
    price       INT          NOT NULL,
    -- 'permanent' (owned for good) or 'rental' (7 days from payment).
    plan        VARCHAR(10)  NOT NULL,
    PRIMARY KEY (order_id, activity_id),
    CONSTRAINT ck_order_items_plan CHECK (plan IN ('permanent', 'rental')),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_items_activity FOREIGN KEY (activity_id) REFERENCES activities (id)
);

-- The right to download an activity's PDF. One row per user and activity, ever.
CREATE TABLE purchase_entitlements (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    activity_id BIGINT      NOT NULL,
    order_id    BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL,
    granted_at  DATETIME(6) NOT NULL,
    revoked_at  DATETIME(6) NULL,
    -- Null: for good. Otherwise the right ends at this moment (UTC); the row stays, so the buyer's list can say so.
    expires_at  DATETIME(6) NULL,
    CONSTRAINT uq_entitlements_user_activity UNIQUE (user_id, activity_id),
    CONSTRAINT fk_entitlements_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_entitlements_activity FOREIGN KEY (activity_id) REFERENCES activities (id),
    CONSTRAINT fk_entitlements_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_entitlements_status CHECK (status IN ('active', 'revoked'))
);

-- A buyer's request to have a paid order refunded. An admin reads the reason and decides.
CREATE TABLE refund_requests (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    reason     VARCHAR(500) NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    admin_note VARCHAR(500) NULL,
    created_at DATETIME(6)  NOT NULL,
    decided_at DATETIME(6)  NULL,
    CONSTRAINT fk_refund_requests_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_refund_requests_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_refund_requests_status CHECK (status IN ('requested', 'approved', 'rejected'))
);
CREATE INDEX ix_refund_requests_status ON refund_requests (status, id);
CREATE INDEX ix_refund_requests_order ON refund_requests (order_id);

-- One row each time a buyer's link delivered a PDF: the record of what was supplied, and the
-- evidence an admin reads when deciding a refund. Kept; the nightly clean-up never touches it.
CREATE TABLE pdf_downloads (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    activity_id   BIGINT      NOT NULL,
    downloaded_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_pdf_downloads_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_pdf_downloads_activity FOREIGN KEY (activity_id) REFERENCES activities (id)
);
CREATE INDEX ix_pdf_downloads_user_activity ON pdf_downloads (user_id, activity_id);

CREATE TABLE cart_items (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    CONSTRAINT uq_cart_items_user_activity UNIQUE (user_id, activity_id),
    CONSTRAINT fk_cart_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_activity FOREIGN KEY (activity_id) REFERENCES activities (id) ON DELETE CASCADE
);
