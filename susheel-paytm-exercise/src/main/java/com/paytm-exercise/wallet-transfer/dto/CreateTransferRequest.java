package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public class CreateTransferRequest {

    @Positive(message = "fromWalletId must be positive")
    private int fromWalletId;

    @Positive(message = "toWalletId must be positive")
    private int toWalletId;

    @Positive(message = "amountPaise must be greater than zero")
    private int amountPaise;

    @NotBlank(message = "idempotencyKey must not be blank")
    private String idempotencyKey;

    public int getFromWalletId() {
        return fromWalletId;
    }

    public void setFromWalletId(int fromWalletId) {
        this.fromWalletId = fromWalletId;
    }

    public int getToWalletId() {
        return toWalletId;
    }

    public void setToWalletId(int toWalletId) {
        this.toWalletId = toWalletId;
    }

    public int getAmountPaise() {
        return amountPaise;
    }

    public void setAmountPaise(int amountPaise) {
        this.amountPaise = amountPaise;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
