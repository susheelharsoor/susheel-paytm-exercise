package com.example.demo.exception;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(int walletId) {
        super("Insufficient balance in wallet: " + walletId);
    }
}
