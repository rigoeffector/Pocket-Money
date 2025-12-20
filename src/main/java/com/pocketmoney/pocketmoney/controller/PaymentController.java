package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.*;
import com.pocketmoney.pocketmoney.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/top-up")
    public ResponseEntity<ApiResponse<PaymentResponse>> topUp(
            @Valid @RequestBody TopUpRequest request) {
        try {
            PaymentResponse response = paymentService.topUp(request.getUserId(), request);
            return ResponseEntity.ok(ApiResponse.success("Top-up initiated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/pay")
    public ResponseEntity<ApiResponse<PaymentResponse>> makePayment(
            @Valid @RequestBody PaymentRequest request) {
        try {
            PaymentResponse response = paymentService.makePayment(request.getUserId(), request);
            return ResponseEntity.ok(ApiResponse.success("Payment processed successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/balance/{userId}")
    public ResponseEntity<ApiResponse<BalanceResponse>> checkBalance(@PathVariable UUID userId) {
        try {
            BalanceResponse response = paymentService.checkBalance(userId);
            return ResponseEntity.ok(ApiResponse.success("Balance retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/status/{mopayTransactionId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> checkTransactionStatus(
            @PathVariable String mopayTransactionId) {
        try {
            PaymentResponse response = paymentService.checkTransactionStatus(mopayTransactionId);
            return ResponseEntity.ok(ApiResponse.success("Transaction status retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

}

