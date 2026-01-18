package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.service.MessagingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test")
public class TestController {

    private static final Logger logger = LoggerFactory.getLogger(TestController.class);

    private final MessagingService messagingService;

    public TestController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    @Data
    public static class TestSmsRequest {
        @NotBlank(message = "Phone number is required")
        private String phone;

        @NotBlank(message = "Message is required")
        private String message;
    }

    /**
     * Test endpoint for sending SMS via Swift.com API
     * POST /api/test/sms/send-json
     * Requires authentication
     */
    @PostMapping("/sms/send-json")
    @PreAuthorize("hasAnyRole('USER', 'RECEIVER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Object>> sendTestSmsSwift(@Valid @RequestBody TestSmsRequest request) {
        try {
            logger.info("Test SMS request received - Phone: {}, Message length: {}", request.getPhone(), request.getMessage().length());
            
            // Normalize phone number (ensure 12 digits with 250 prefix)
            String normalizedPhone = normalizePhoneNumber(request.getPhone());
            
            // Send SMS using MessagingService
            messagingService.sendSms(request.getMessage(), normalizedPhone);
            
            logger.info("Test SMS sent successfully to: {}", normalizedPhone);
            
            return ResponseEntity.ok(ApiResponse.success(
                "SMS sent successfully via Swift.com",
                new TestSmsResponse(normalizedPhone, request.getMessage(), "SMS queued for delivery via Swift.com")
            ));
        } catch (Exception e) {
            logger.error("Error sending test SMS via Swift.com: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to send SMS via Swift.com: " + e.getMessage()));
        }
    }

    /**
     * Test endpoint for sending SMS via BeSoft SMS API
     * POST /api/test/sms/besoft
     * Requires authentication
     */
    @PostMapping("/sms/besoft")
    @PreAuthorize("hasAnyRole('USER', 'RECEIVER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Object>> sendTestSmsBeSoft(@Valid @RequestBody TestSmsRequest request) {
        try {
            logger.info("Test BeSoft SMS request received - Phone: {}, Message length: {}", request.getPhone(), request.getMessage().length());
            
            // Normalize phone number (ensure 12 digits with 250 prefix)
            String normalizedPhone = normalizePhoneNumber(request.getPhone());
            
            // Send SMS using BeSoft SMS (by temporarily setting SMS type to besoftsms)
            messagingService.sendSmsViaBeSoft(request.getMessage(), normalizedPhone);
            
            logger.info("Test BeSoft SMS sent successfully to: {}", normalizedPhone);
            
            return ResponseEntity.ok(ApiResponse.success(
                "SMS sent successfully via BeSoft",
                new TestSmsResponse(normalizedPhone, request.getMessage(), "SMS queued for delivery via BeSoft")
            ));
        } catch (Exception e) {
            logger.error("Error sending test SMS via BeSoft: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to send SMS via BeSoft: " + e.getMessage()));
        }
    }

    /**
     * Test endpoint for sending SMS via Swift.com API with BEPAY sender ID
     * POST /api/test/sms/bepay
     * Requires authentication
     */
    @PostMapping("/sms/bepay")
    @PreAuthorize("hasAnyRole('USER', 'RECEIVER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Object>> sendTestSmsBEPAY(@Valid @RequestBody TestSmsRequest request) {
        try {
            logger.info("Test BEPAY SMS request received - Phone: {}, Message length: {}", request.getPhone(), request.getMessage().length());
            
            // Normalize phone number (ensure 12 digits with 250 prefix)
            String normalizedPhone = normalizePhoneNumber(request.getPhone());
            
            // Send SMS using BEPAY configuration
            messagingService.sendSmsViaBEPAY(request.getMessage(), normalizedPhone);
            
            logger.info("Test BEPAY SMS sent successfully to: {}", normalizedPhone);
            
            return ResponseEntity.ok(ApiResponse.success(
                "SMS sent successfully via BEPAY",
                new TestSmsResponse(normalizedPhone, request.getMessage(), "SMS queued for delivery via BEPAY")
            ));
        } catch (Exception e) {
            logger.error("Error sending test SMS via BEPAY: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to send SMS via BEPAY: " + e.getMessage()));
        }
    }

    /**
     * Normalize phone number to 12 digits with 250 prefix
     */
    private String normalizePhoneNumber(String phone) {
        if (phone == null) {
            throw new IllegalArgumentException("Phone number cannot be null");
        }
        
        // Remove all non-digit characters
        String digitsOnly = phone.replaceAll("[^0-9]", "");
        
        // Handle different formats
        if (digitsOnly.startsWith("250") && digitsOnly.length() == 12) {
            // Already in correct format: 250XXXXXXXXX
            return digitsOnly;
        } else if (digitsOnly.startsWith("0") && digitsOnly.length() == 10) {
            // Format: 0XXXXXXXXX -> 250XXXXXXXXX
            return "250" + digitsOnly.substring(1);
        } else if (digitsOnly.length() == 9) {
            // Format: XXXXXXXXX -> 250XXXXXXXXX
            return "250" + digitsOnly;
        } else {
            // Try to handle other cases
            if (digitsOnly.length() > 9) {
                // If it's longer, take last 9 digits
                String last9 = digitsOnly.substring(digitsOnly.length() - 9);
                return "250" + last9;
            } else {
                throw new IllegalArgumentException("Invalid phone number format: " + phone);
            }
        }
    }

    @Data
    public static class TestSmsResponse {
        private String phone;
        private String message;
        private String status;

        public TestSmsResponse(String phone, String message, String status) {
            this.phone = phone;
            this.message = message;
            this.status = status;
        }
    }
}

