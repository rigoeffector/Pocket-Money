package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.service.EfashePaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class MopayPaymentCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(MopayPaymentCallbackController.class);

    private final EfashePaymentService efashePaymentService;

    public MopayPaymentCallbackController(EfashePaymentService efashePaymentService) {
        this.efashePaymentService = efashePaymentService;
    }

    /**
     * Mopay webhook callback endpoint
     * POST /api/v1/payments/callback
     * Receives JWT-encoded webhook notifications from Mopay
     */
    @PostMapping("/callback")
    public ResponseEntity<java.util.Map<String, Object>> handleMopayPaymentWebhook(@RequestBody String jwtToken) {
        try {
            logger.info("Received Mopay webhook callback - JWT Token: {}", jwtToken);
            efashePaymentService.handleMopayPaymentWebhook(jwtToken);
            java.util.Map<String, Object> response = new java.util.HashMap<>();
            response.put("status", HttpStatus.OK.value());
            response.put("message", "Webhook received successfully");
            response.put("transactionId", null);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            logger.error("Error processing Mopay webhook: ", e);
            java.util.Map<String, Object> response = new java.util.HashMap<>();
            response.put("status", HttpStatus.NOT_FOUND.value());
            response.put("message", e.getMessage() != null ? e.getMessage() : "Error processing webhook");
            response.put("transactionId", null);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
    }


    // USE CASE FOR MANUAL TESTING
}
