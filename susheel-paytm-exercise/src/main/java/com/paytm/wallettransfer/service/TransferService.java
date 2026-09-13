package com.paytm.wallettransfer.service;

import com.paytm.wallettransfer.dto.TransferResponse;
import com.paytm.wallettransfer.entity.Transfer;
import com.paytm.wallettransfer.entity.Wallet;
import com.paytm.wallettransfer.exception.BadRequestException;
import com.paytm.wallettransfer.exception.DeclinedReplayException;
import com.paytm.wallettransfer.exception.ForbiddenException;
import com.paytm.wallettransfer.exception.InsufficientBalanceException;
import com.paytm.wallettransfer.exception.ResourceNotFoundException;
import com.paytm.wallettransfer.logging.StructuredEventLogger;
import com.paytm.wallettransfer.repository.TransferRepository;
import com.paytm.wallettransfer.repository.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;
    private final StructuredEventLogger eventLogger;
    private final TransferExecutor transferExecutor;

    public TransferService(TransferRepository transferRepository,
                           WalletRepository walletRepository,
                           StructuredEventLogger eventLogger,
                           TransferExecutor transferExecutor) {
        this.transferRepository = transferRepository;
        this.walletRepository = walletRepository;
        this.eventLogger = eventLogger;
        this.transferExecutor = transferExecutor;
    }

    /**
     * Public entry point — NOT @Transactional itself so that the DataIntegrityViolationException
     * thrown by transferExecutor.executeTransfer (which completes and closes its own REQUIRES_NEW
     * transaction before throwing) can be caught here in a clean, non-poisoned state.
     *
     * executeTransfer and saveDeclinedTransfer live in TransferExecutor (a separate Spring bean)
     * so their @Transactional(REQUIRES_NEW) annotations are honoured by the Spring proxy.
     * Calling @Transactional methods on 'this' would bypass the proxy and silently drop the
     * transaction boundary — exactly the bug this split fixes.
     */
    public TransferResponse initiateTransfer(int fromWalletId, int toWalletId, int amountPaise, String idempotencyKey, int callerUserId) {
        // Ownership check — fast-fail before acquiring any DB locks
        Wallet fromWallet = walletRepository.findById(fromWalletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + fromWalletId));
        if (fromWallet.getUserId() != callerUserId) {
            throw new ForbiddenException("You do not own wallet: " + fromWalletId);
        }

        if (fromWalletId == toWalletId) {
            eventLogger.logDomainEvent("transfer_declined", Map.of(
                    "reason", "Cannot transfer to the same wallet",
                    "fromWalletId", fromWalletId,
                    "toWalletId", toWalletId,
                    "amountPaise", amountPaise,
                    "idempotencyKey", idempotencyKey
            ));
            throw new BadRequestException("Cannot transfer to the same wallet");
        }

        try {
            return transferExecutor.executeTransfer(fromWalletId, toWalletId, amountPaise, idempotencyKey);
        } catch (DeclinedReplayException e) {
            // The DECLINED record already exists in the DB (it's what triggered the replay path).
            // Skip saveDeclinedTransfer — the row is already there. Just rethrow for 422.
            throw e;
        } catch (InsufficientBalanceException e) {
            // New decline: executeTransfer's transaction rolled back — persist a DECLINED record
            // in a fresh transaction so GET /transfers/{id} can return the outcome.
            transferExecutor.saveDeclinedTransfer(fromWalletId, toWalletId, amountPaise, idempotencyKey);
            throw e;
        } catch (DataIntegrityViolationException e) {
            // The executeTransfer transaction rolled back because another concurrent transaction
            // committed the same idempotency_key first. That transaction is now fully committed,
            // so we can safely re-fetch in a fresh read here.
            Transfer committed = transferRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new RuntimeException("Unexpected: idempotency key not found after conflict", e));
            return transferExecutor.handleIdempotencyReplay(committed, fromWalletId, toWalletId, amountPaise, idempotencyKey);
        }
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(int id) {
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found: " + id));
        return new TransferResponse(transfer.getId(), transfer.getStatus().name(), transfer.getIdempotencyKey());
    }
}
