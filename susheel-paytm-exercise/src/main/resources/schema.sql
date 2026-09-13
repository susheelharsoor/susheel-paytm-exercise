-- Schema managed by SQL so the CHECK constraint is always present,
-- even on databases where Hibernate ran with ddl-auto=update (which
-- skips adding new constraints to existing tables).

CREATE TABLE IF NOT EXISTS wallets (
    id         SERIAL PRIMARY KEY,
    user_id    INT    NOT NULL UNIQUE,
    balance    INT    NOT NULL DEFAULT 0,
    CONSTRAINT wallets_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE IF NOT EXISTS transfers (
    id               SERIAL PRIMARY KEY,
    from_wallet_id   INT          NOT NULL REFERENCES wallets(id),
    to_wallet_id     INT          NOT NULL REFERENCES wallets(id),
    amount_paise     INT          NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL UNIQUE
);

-- Ensure the CHECK constraint exists on the wallets table even if the table
-- was previously created without it (e.g. via ddl-auto=update).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'wallets_balance_non_negative'
    ) THEN
        ALTER TABLE wallets
            ADD CONSTRAINT wallets_balance_non_negative CHECK (balance >= 0);
    END IF;
END$$;
