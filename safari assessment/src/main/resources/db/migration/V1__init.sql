-- V1__init.sql
-- M-PESA Wallet Service schema

CREATE TABLE wallets (
    wallet_id   VARCHAR(20)  PRIMARY KEY,
    status      VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE | FROZEN
    is_merchant BOOLEAN      NOT NULL DEFAULT FALSE,
    balance     BIGINT       NOT NULL DEFAULT 0,              -- in santim
    reserved    BIGINT       NOT NULL DEFAULT 0,              -- in santim
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_balance   CHECK (balance  >= 0),
    CONSTRAINT chk_reserved  CHECK (reserved >= 0),
    CONSTRAINT chk_available CHECK (balance  >= reserved),
    CONSTRAINT chk_status    CHECK (status IN ('ACTIVE','FROZEN'))
);

CREATE TABLE transfers (
    transfer_id  VARCHAR(30)  PRIMARY KEY,
    from_wallet  VARCHAR(20)  NOT NULL REFERENCES wallets(wallet_id),
    to_wallet    VARCHAR(20)  NOT NULL REFERENCES wallets(wallet_id),
    amount       BIGINT       NOT NULL,
    reference    VARCHAR(64)  NOT NULL,
    status       VARCHAR(15)  NOT NULL DEFAULT 'COMPLETED',  -- COMPLETED | REVERSED
    reversal_id  VARCHAR(30),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reversed_at  TIMESTAMPTZ,
    CONSTRAINT chk_amount_positive CHECK (amount > 0)
);

CREATE TABLE payouts (
    payout_id    VARCHAR(30)  PRIMARY KEY,
    wallet_id    VARCHAR(20)  NOT NULL REFERENCES wallets(wallet_id),
    bank_account VARCHAR(50)  NOT NULL,
    amount       BIGINT       NOT NULL,
    status       VARCHAR(15)  NOT NULL DEFAULT 'PENDING',    -- PENDING | SUCCEEDED | FAILED
    bank_ref     VARCHAR(100),
    conflict     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_payout_amount CHECK (amount > 0),
    CONSTRAINT chk_payout_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED'))
);

-- Idempotency store
CREATE TABLE idempotency_keys (
    idem_key     VARCHAR(64)  PRIMARY KEY,
    endpoint     VARCHAR(50)  NOT NULL,
    body_hash    VARCHAR(64)  NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS', -- IN_PROGRESS | DONE
    response_status INT,
    response_body   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
);

-- Ledger entries (for statement endpoint)
CREATE TABLE ledger_entries (
    entry_id     VARCHAR(30)  PRIMARY KEY,
    wallet_id    VARCHAR(20)  NOT NULL REFERENCES wallets(wallet_id),
    type         VARCHAR(20)  NOT NULL,                      -- TRANSFER_IN | TRANSFER_OUT | PAYOUT
    amount       BIGINT       NOT NULL,                      -- negative for debits
    balance_after BIGINT      NOT NULL,
    reference    VARCHAR(30)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_wallet_created ON ledger_entries (wallet_id, created_at DESC, entry_id DESC);
CREATE INDEX idx_idempotency_expires  ON idempotency_keys (expires_at);
CREATE INDEX idx_payouts_status       ON payouts (status) WHERE status = 'PENDING';
