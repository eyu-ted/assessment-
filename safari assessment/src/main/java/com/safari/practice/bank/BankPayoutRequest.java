package com.safari.practice.bank;

import lombok.Data;

/**
 * Outgoing payout request to the fake bank (section 4.1).
 */
@Data
public class BankPayoutRequest {
    private String payout_id;
    private String bank_account;
    private long   amount;
}
