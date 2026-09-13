package com.example.demo.service;

import com.example.demo.dto.WalletResponse;
import com.example.demo.entity.Wallet;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.WalletRepository;
import com.example.demo.logging.StructuredEventLogger;
import com.example.demo.metrics.MetricsService;
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
            return createWalletInNewTransaction(userId, initialBalance);
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
