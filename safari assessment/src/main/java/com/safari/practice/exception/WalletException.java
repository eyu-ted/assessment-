package com.safari.practice.exception;

import org.springframework.http.HttpStatus;

public class WalletException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public WalletException(String errorCode, String message, HttpStatus httpStatus) {
        super(message);
        this.errorCode  = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode()   { return errorCode; }
    public HttpStatus getHttpStatus() { return httpStatus; }

    // ── 400 ──────────────────────────────────────────────────────────────
    public static WalletException validationFailed(String msg) {
        return new WalletException("VALIDATION_FAILED", msg, HttpStatus.BAD_REQUEST);
    }

    // ── 404 ──────────────────────────────────────────────────────────────
    public static WalletException walletNotFound(String id) {
        return new WalletException("WALLET_NOT_FOUND",
                "Wallet not found: " + id, HttpStatus.NOT_FOUND);
    }

    public static WalletException transferNotFound(String id) {
        return new WalletException("TRANSFER_NOT_FOUND",
                "Transfer not found: " + id, HttpStatus.NOT_FOUND);
    }

    public static WalletException payoutNotFound(String id) {
        return new WalletException("PAYOUT_NOT_FOUND",
                "Payout not found: " + id, HttpStatus.NOT_FOUND);
    }

    // ── 409 ──────────────────────────────────────────────────────────────
    public static WalletException idempotencyConflict(String key) {
        return new WalletException("IDEMPOTENCY_CONFLICT",
                "Idempotency key reused with a different body: " + key, HttpStatus.CONFLICT);
    }

    public static WalletException requestInProgress(String key) {
        return new WalletException("REQUEST_IN_PROGRESS",
                "Original request still running: " + key, HttpStatus.CONFLICT);
    }

    public static WalletException alreadyReversed(String id) {
        return new WalletException("ALREADY_REVERSED",
                "Transfer already reversed: " + id, HttpStatus.CONFLICT);
    }

    // ── 422 ──────────────────────────────────────────────────────────────
    public static WalletException insufficientFunds(String walletId, long available, long requested) {
        return new WalletException("INSUFFICIENT_FUNDS",
                walletId + " available " + available + ", requested " + requested,
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public static WalletException walletNotActive(String walletId) {
        return new WalletException("WALLET_NOT_ACTIVE",
                "Wallet is not ACTIVE: " + walletId, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public static WalletException sameWallet(String walletId) {
        return new WalletException("SAME_WALLET",
                "Source and destination are the same wallet: " + walletId,
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public static WalletException notReversible(String id) {
        return new WalletException("NOT_REVERSIBLE",
                "Transfer is not in COMPLETED state: " + id, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ── 503 ──────────────────────────────────────────────────────────────
    public static WalletException serviceUnavailable() {
        return new WalletException("SERVICE_UNAVAILABLE",
                "Database is unreachable", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
