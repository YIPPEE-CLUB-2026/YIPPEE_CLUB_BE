-- V1 is applied and frozen. Everything after it goes here or in a later file.

-- One row per sign-in identity at a provider (kakao, naver, google). The provider's own user
-- number names the account; the email is used once, to find or create the user.
CREATE TABLE social_accounts (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    provider         VARCHAR(20)  NOT NULL,
    -- Compared to the letter: the default comparison ignores case, and Naver's numbers are mixed-case text.
    provider_user_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    -- False: the provider did not vouch for the email this account was made on. A password reset
    -- (proof of the mailbox) then removes the row.
    email_verified   BOOLEAN      NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    CONSTRAINT uq_social_accounts_identity UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_social_accounts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_social_accounts_user ON social_accounts (user_id);

-- Where a PDF went: a link that was passed on can then be told from the buyer's own download.
ALTER TABLE pdf_downloads ADD COLUMN ip_address VARCHAR(45) NULL;
ALTER TABLE pdf_downloads ADD COLUMN user_agent VARCHAR(255) NULL;
