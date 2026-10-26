-- V2__seed.sql
-- Seed data per spec section II

-- Wallets W001–W100: ACTIVE, 1,000,000 santim each
INSERT INTO wallets (wallet_id, status, is_merchant, balance, reserved)
SELECT
    'W' || LPAD(i::TEXT, 3, '0'),
    'ACTIVE',
    FALSE,
    1000000,
    0
FROM generate_series(1, 100) AS i;

-- Wallets W101–W105: FROZEN, 1,000,000 santim each
INSERT INTO wallets (wallet_id, status, is_merchant, balance, reserved)
SELECT
    'W' || LPAD(i::TEXT, 3, '0'),
    'FROZEN',
    FALSE,
    1000000,
    0
FROM generate_series(101, 105) AS i;

-- Merchant M001: ACTIVE, 50,000,000 santim
INSERT INTO wallets (wallet_id, status, is_merchant, balance, reserved)
VALUES ('M001', 'ACTIVE', TRUE, 50000000, 0);
