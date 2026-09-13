package com.paytm.wallettransfer.service;

import com.paytm.wallettransfer.dto.TransferResponse;
import com.paytm.wallettransfer.entity.Transfer;
import com.paytm.wallettransfer.entity.Wallet;
import com.paytm.wallettransfer.enums.TransferStatus;
import com.paytm.wallettransfer.exception.ConflictException;
import com.paytm.wallettransfer.exception.DeclinedReplayException;
import com.paytm.wallettransfer.exception.InsufficientBalanceException;
import com.paytm.wallettransfer.exception.ResourceNotFoundException;
import com.paytm.wallettransfer.logging.StructuredEventLogger;
import com.paytm.wallettransfer.metrics.MetricsService;
import com.paytm.wallettransfer.repository.TransferRepository;
import com.paytm.wallettransfer.repository.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Holds the transactional units for transfer execution.
 *
 * Extracted into its own Spring bean so that calls from TransferService go
 * through the Spring proxy — making @Transactional(REQUIRES_NEW) effective.
 * Calling @Transactional methods on 'this' within the same bean bypasses the
 * proxy and silently drops the transaction boundary, which causes
 * "SELECT ... FOR UPDATE" to fail outside any transaction.
 */
@Service
public class TransferExecutor {

    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;
    private final StructuredEventLogger eventLogger;
    private final MetricsService metricsService;

    public TransferExecutor(TransferRepository transferRepository,
                            WalletRepository walletRepository,
                            StructuredEventLogger eventLogger,
                            MetricsService metricsService) {
        this.transferRepository = transferRepository;
        this.walletRepository = walletRepository;
        this.eventLogger = eventLogger;
        this.metricsService = metricsService;
    }

    /**
     * The actual transactional unit: locks both wallets in sorted order, checks idempotency,
     * applies debit+credit, inserts the Transfer row. All four writes commit atomically.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransferResponse executeTransfer(int fromWalletId, int toWalletId, int amountPaise, String idempotencyKey) {
        // Fast-path idempotency check before locking
        Optional<Transfer> existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return handleIdempotencyReplay(existing.get(), fromWalletId, toWalletId, amountPaise, idempotencyKey);
        }

        // Deterministic lock ordering to prevent deadlocks (lock smaller ID first)
        int firstId = Math.min(fromWalletId, toWalletId);
        int secondId = Math.max(fromWalletId, toWalletId);

        Wallet firstWallet = walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> {
                    eventLogger.logDomainEvent("transfer_declined", Map.of(
                            "reason", "Wallet not found: " + firstId,
                            "fromWalletId", fromWalletId,
                            "toWalletId", toWalletId,
                            "amountPaise", amountPaise,
                            "idempotencyKey", idempotencyKey
                    ));
                    return new ResourceNotFoundException("Wallet not found: " + firstId);
                });
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> {
                    eventLogger.logDomainEvent("transfer_declined", Map.of(
                            "reason", "Wallet not found: " + secondId,
                            "fromWalletId", fromWalletId,
                            "toWalletId", toWalletId,
                            "amountPaise", amountPaise,
                            "idempotencyKey", idempotencyKey
                    ));
                    return new ResourceNotFoundException("Wallet not found: " + secondId);
                });

        // Re-check idempotency while holding locks (covers the window between first check and lock acquisition)
        Optional<Transfer> lockedExisting = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (lockedExisting.isPresent()) {
            return handleIdempotencyReplay(lockedExisting.get(), fromWalletId, toWalletId, amountPaise, idempotencyKey);
        }

        Wallet fromWallet = (fromWalletId == firstId) ? firstWallet : secondWallet;
        Wallet toWallet = (toWalletId == firstId) ? firstWallet : secondWallet;

        try {
            fromWallet.debit(amountPaise);
            eventLogger.logDomainEvent("debited", Map.of(
                    "walletId", fromWallet.getId(),
                    "amountPaise", amountPaise,
                    "remainingBalance", fromWallet.getBalance()
            ));
        } catch (InsufficientBalanceException e) {
            metricsService.incrementDomainCounter("transfers_declined_insufficient_funds");
            eventLogger.logDomainEvent("transfer_declined", Map.of(
                    "reason", "Insufficient balance",
                    "walletId", fromWallet.getId(),
                    "attemptedDebit", amountPaise,
                    "currentBalance", fromWallet.getBalance(),
                    "idempotencyKey", idempotencyKey
            ));
            throw e;
        }

        toWallet.credit(amountPaise);
        eventLogger.logDomainEvent("credited", Map.of(
                "walletId", toWallet.getId(),
                "amountPaise", amountPaise,
                "newBalance", toWallet.getBalance()
        ));

        walletRepository.save(fromWallet);
        walletRepository.save(toWallet);

        Transfer saved = transferRepository.save(new Transfer(fromWallet, toWallet, amountPaise, idempotencyKey));

        metricsService.incrementDomainCounter("transfers_created");
        eventLogger.logDomainEvent("transfer_created", Map.of(
                "transferId", saved.getId(),
                "fromWalletId", fromWallet.getId(),
                "toWalletId", toWallet.getId(),
                "amountPaise", amountPaise,
                "status", saved.getStatus().name(),
                "idempotencyKey", idempotencyKey
        ));

        return new TransferResponse(saved.getId(), saved.getStatus().name(), saved.getIdempotencyKey());
    }

    /**
     * Persists a DECLINED Transfer row in its own transaction, independent of any prior rollback.
     * Called after executeTransfer rolls back due to InsufficientBalanceException.
     * Ignores DataIntegrityViolationException in case a concurrent request already saved a declined
     * record for the same key.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveDeclinedTransfer(int fromWalletId, int toWalletId, int amountPaise, String idempotencyKey) {
        // Skip if already recorded (concurrent declined insert race)
        if (transferRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return;
        }
        Wallet fromWallet = walletRepository.findById(fromWalletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + fromWalletId));
        Wallet toWallet = walletRepository.findById(toWalletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + toWalletId));
        try {
            transferRepository.saveAndFlush(
                    new Transfer(fromWallet, toWallet, amountPaise, idempotencyKey, TransferStatus.DECLINED));
        } catch (DataIntegrityViolationException ignored) {
            // Another concurrent thread saved the declined record first — safe to ignore.
        }
    }

    TransferResponse handleIdempotencyReplay(Transfer t, int fromWalletId, int toWalletId, int amountPaise, String idempotencyKey) {
        boolean sameBody = t.getFromWallet().getId() == fromWalletId
                && t.getToWallet().getId() == toWalletId
                && t.getAmountPaise() == amountPaise;
        if (!sameBody) {
            eventLogger.logDomainEvent("transfer_declined", Map.of(
                    "reason", "Idempotency key body mismatch",
                    "idempotencyKey", idempotencyKey,
                    "existingTransferId", t.getId()
            ));
            throw new ConflictException(
                    "Idempotency key already used with a different request body: " + idempotencyKey);
        }

        metricsService.incrementDomainCounter("idempotent_replays");
        eventLogger.logDomainEvent("idempotent_replay_hit", Map.of(
                "transferId", t.getId(),
                "idempotencyKey", idempotencyKey,
                "status", t.getStatus().name()
        ));

        // A retry of a previously-declined transfer must return the same error, not a 200 replay.
        if (t.getStatus() == TransferStatus.DECLINED) {
            throw new DeclinedReplayException(t.getFromWallet().getId());
        }

        return new TransferResponse(t.getId(), t.getStatus().name(), t.getIdempotencyKey(), true);
    }
}
