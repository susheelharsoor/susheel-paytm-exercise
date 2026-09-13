package com.example.demo.controller;

import com.example.demo.dto.CreateWalletRequest;
import com.example.demo.dto.WalletResponse;
import com.example.demo.security.AuthFilter;
import com.example.demo.service.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(
            @Valid @RequestBody(required = false) CreateWalletRequest requestBody,
            HttpServletRequest httpRequest) {
        int userId = (int) httpRequest.getAttribute(AuthFilter.USER_ID_ATTR);
        int initialBalance = (requestBody != null) ? requestBody.getInitialBalance() : 0;
        WalletResponse response = walletService.getOrCreateWallet(userId, initialBalance);
        HttpStatus status = response.isCreated() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{id}")
    public WalletResponse getWallet(@PathVariable int id) {
        return walletService.getWallet(id);
    }
}

