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

