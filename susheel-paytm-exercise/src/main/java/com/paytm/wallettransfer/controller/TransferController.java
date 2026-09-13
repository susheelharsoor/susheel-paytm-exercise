package com.paytm.wallettransfer.controller;

import com.paytm.wallettransfer.dto.CreateTransferRequest;
import com.paytm.wallettransfer.dto.TransferResponse;
import com.paytm.wallettransfer.security.AuthFilter;
import com.paytm.wallettransfer.service.TransferService;
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
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> initiateTransfer(
            @Valid @RequestBody CreateTransferRequest request,
            HttpServletRequest httpRequest) {
        int callerUserId = (int) httpRequest.getAttribute(AuthFilter.USER_ID_ATTR);
        TransferResponse response = transferService.initiateTransfer(
                request.getFromWalletId(),
                request.getToWalletId(),
                request.getAmountPaise(),
                request.getIdempotencyKey(),
                callerUserId
        );
        HttpStatus status = response.isReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{id}")
    public TransferResponse getTransfer(@PathVariable int id) {
        return transferService.getTransfer(id);
    }
}

