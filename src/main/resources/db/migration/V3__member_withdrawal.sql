-- V1 and V2 are applied and frozen.

-- Set when the member leaves. The row stays, emptied of everything personal, because orders,
-- refund requests and the download record point at it.
ALTER TABLE users ADD COLUMN withdrawn_at DATETIME(6) NULL;

-- Who a withdrawn buyer was, kept apart from the live members for as long as the trade records
-- must be kept (the e-commerce act: contract, payment and supply records, five years). Only a
-- member with at least one order gets a row; the nightly clean-up removes it after purge_after.
CREATE TABLE withdrawn_members (
    user_id      BIGINT       PRIMARY KEY,
    email        VARCHAR(254) NOT NULL,
    name         VARCHAR(100) NOT NULL,
    phone        VARCHAR(20)  NOT NULL,
    withdrawn_at DATETIME(6)  NOT NULL,
    purge_after  DATETIME(6)  NOT NULL,
    CONSTRAINT fk_withdrawn_members_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX ix_withdrawn_members_purge ON withdrawn_members (purge_after);
