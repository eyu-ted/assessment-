package com.safari.practice.bank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Response from POST /bank/payouts (section 4.1).
 * result is "ACCEPTED" or "REJECTED".
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BankPayoutResponse {
    private String result;    // ACCEPTED | REJECTED
    private String bank_ref;
    private String reason;
    private String status;    // for GET /bank/payouts/{id}
}
