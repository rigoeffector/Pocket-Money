package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.EfasheInitiateRequest;
import com.pocketmoney.pocketmoney.dto.EfasheInitiateResponse;
import com.pocketmoney.pocketmoney.dto.MoPayResponse;
import com.pocketmoney.pocketmoney.service.EfashePaymentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/efashe")
public class EfasheController {

    private static final Logger logger = LoggerFactory.getLogger(EfasheController.class);

    private final EfashePaymentService efashePaymentService;

    public EfasheController(EfashePaymentService efashePaymentService) {
        this.efashePaymentService = efashePaymentService;
    }

    /**
     * Initiate EFASHE payment with MoPay (Admin only)
     * POST /api/efashe/initiate
     */
    @PostMapping("/initiate")
    public ResponseEntity<ApiResponse<EfasheInitiateResponse>> initiatePayment(
            @Valid @RequestBody EfasheInitiateRequest request) {
        try {
            EfasheInitiateResponse response = efashePaymentService.initiatePayment(request);
            return ResponseEntity.ok(ApiResponse.success("EFASHE payment initiated successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error initiating EFASHE payment: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Check EFASHE transaction status using MoPay transaction ID
     * GET /api/efashe/status/{transactionId}
     */
    @GetMapping("/status/{transactionId}")
    public ResponseEntity<ApiResponse<MoPayResponse>> checkTransactionStatus(
            @PathVariable("transactionId") String transactionId) {
        try {
            MoPayResponse response = efashePaymentService.checkTransactionStatus(transactionId);
            return ResponseEntity.ok(ApiResponse.success("Transaction status retrieved successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error checking EFASHE transaction status: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}
