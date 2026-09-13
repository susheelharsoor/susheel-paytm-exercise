package com.paytm.wallettransfer.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * Body for POST /wallets.
 * userId is no longer supplied here — it is derived from the bearer token.
 */
public class CreateWalletRequest {

    @PositiveOrZero(message = "initialBalance cannot be negative")
    private int initialBalance;

    public int getInitialBalance() { return initialBalance; }
    public void setInitialBalance(int initialBalance) { this.initialBalance = initialBalance; }
}
