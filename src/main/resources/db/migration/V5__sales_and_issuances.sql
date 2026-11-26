-- V1 to V4 are applied and frozen.

-- When the money went back. Sales are counted on the day of payment and refunds on the day of the
-- refund, so a refund needs its own time. An order refunded before this column gets its payment time.
ALTER TABLE orders ADD COLUMN refunded_at DATETIME(6) NULL;
UPDATE orders SET refunded_at = COALESCE(paid_at, created_at) WHERE status = 'refunded';
CREATE INDEX ix_orders_paid_at ON orders (paid_at);
CREATE INDEX ix_orders_refunded_at ON orders (refunded_at);

-- The code printed on a member's copy of a sheet. One per member and sheet, for good: downloading
-- again, or buying again after a refund, prints the same code. The code is random and says nothing
-- about the member; only this table ties it to one.
CREATE TABLE worksheet_issuances (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    serial_code VARCHAR(32) NOT NULL,
    user_id     BIGINT      NOT NULL,
    activity_id BIGINT      NOT NULL,
    -- The order the right came from when the code was issued. Null for a free sheet taken without an order.
    order_id    BIGINT      NULL,
    issued_at   DATETIME(6) NOT NULL,
    CONSTRAINT uq_issuances_serial UNIQUE (serial_code),
    CONSTRAINT uq_issuances_user_activity UNIQUE (user_id, activity_id),
    CONSTRAINT fk_issuances_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_issuances_activity FOREIGN KEY (activity_id) REFERENCES activities (id),
    CONSTRAINT fk_issuances_order FOREIGN KEY (order_id) REFERENCES orders (id)
);
CREATE INDEX ix_issuances_activity ON worksheet_issuances (activity_id);

-- Which copy each delivery was. Null on the deliveries made before codes existed.
ALTER TABLE pdf_downloads ADD COLUMN issuance_id BIGINT NULL;
ALTER TABLE pdf_downloads ADD CONSTRAINT fk_pdf_downloads_issuance FOREIGN KEY (issuance_id) REFERENCES worksheet_issuances (id);
