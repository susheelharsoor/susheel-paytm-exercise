package com.paytm.wallettransfer.exception;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(int walletId) {
        super("Insufficient balance in wallet: " + walletId);
    }
}
