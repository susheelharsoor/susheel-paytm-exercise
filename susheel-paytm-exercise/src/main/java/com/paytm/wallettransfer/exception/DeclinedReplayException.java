package com.paytm.wallettransfer.exception;

/**
 * Thrown when a transfer replay is detected and the original transfer was DECLINED.
 *
 * Extends InsufficientBalanceException so the GlobalExceptionHandler still maps it
 * to 422 Unprocessable Entity — callers see the same HTTP response as a fresh decline.
 *
 * Keeping it as a distinct subclass lets TransferService.initiateTransfer skip the
 * unnecessary saveDeclinedTransfer() call: the DECLINED row is already in the DB
 * (it's the record that triggered the replay path in the first place).
 */
public class DeclinedReplayException extends InsufficientBalanceException {
    public DeclinedReplayException(int walletId) {
        super(walletId);
    }
}
