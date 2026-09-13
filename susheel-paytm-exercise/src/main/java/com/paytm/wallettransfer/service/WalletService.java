package com.paytm.wallettransfer.service;

import com.paytm.wallettransfer.dto.WalletResponse;
import com.paytm.wallettransfer.entity.Wallet;
import com.paytm.wallettransfer.exception.ResourceNotFoundException;
import com.paytm.wallettransfer.repository.WalletRepository;
import com.paytm.wallettransfer.logging.StructuredEventLogger;
import com.paytm.wallettransfer.metrics.MetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final StructuredEventLogger eventLogger;
    private final MetricsService metricsService;

    /**
     * Self-injection via @Lazy to call createWalletInNewTransaction through the Spring proxy.
     * Calling 'this.createWalletInNewTransaction(...)' directly bypasses the proxy and
     * silently drops the @Transactional(REQUIRES_NEW) boundary.
     */
    @Autowired
    @Lazy
    private WalletService self;

    public WalletService(WalletRepository walletRepository, StructuredEventLogger eventLogger, MetricsService metricsService) {
        this.walletRepository = walletRepository;
        this.eventLogger = eventLogger;
        this.metricsService = metricsService;
    }

    public WalletResponse getOrCreateWallet(int userId, int initialBalance) {
        Optional<Wallet> existing = walletRepository.findByUserId(userId);
        if (existing.isPresent()) {
            Wallet wallet = existing.get();
            return new WalletResponse(wallet.getId(), wallet.getUserId(), wallet.getBalance(), false,
                    "Wallet already exists. initialBalance was ignored.");
        }

        try {
            return self.createWalletInNewTransaction(userId, initialBalance);
        } catch (DataIntegrityViolationException e) {
            // concurrent request created the wallet between our check and save — fetch and return it in a clean transaction
            Wallet wallet = walletRepository.findByUserId(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for userId: " + userId));
            return new WalletResponse(wallet.getId(), wallet.getUserId(), wallet.getBalance(), false,
                    "Wallet already exists. initialBalance was ignored.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletResponse createWalletInNewTransaction(int userId, int initialBalance) {
        Optional<Wallet> existing = walletRepository.findByUserId(userId);
        if (existing.isPresent()) {
            Wallet wallet = existing.get();
            return new WalletResponse(wallet.getId(), wallet.getUserId(), wallet.getBalance(), false,
                    "Wallet already exists. initialBalance was ignored.");
        }
        Wallet saved = walletRepository.saveAndFlush(new Wallet(userId, initialBalance));
        metricsService.incrementDomainCounter("wallets_created");
        eventLogger.logDomainEvent("wallet_created", Map.of(
                "walletId", saved.getId(),
                "userId", saved.getUserId(),
                "initialBalance", saved.getBalance()
        ));
        return new WalletResponse(saved.getId(), saved.getUserId(), saved.getBalance(), true);
    }

    @Transactional(readOnly = true)
    public WalletResponse getWallet(int id) {
        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + id));
        return new WalletResponse(wallet.getId(), wallet.getUserId(), wallet.getBalance(), false);
    }
}
