package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.service.MessagingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test/sms")
public class SmsTestController {

    private static final Logger logger = LoggerFactory.getLogger(SmsTestController.class);

    private final MessagingService messagingService;

    public SmsTestController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    /**
     * Test endpoint to send SMS
     * POST /api/test/sms/send
     * 
     * Request body:
     * {
     *   "phone": "250784638201",
     *   "message": "Test SMS message"
     * }
     * 
     * This endpoint is for testing SMS functionality (Swiftcom or BeSoft)
     */
    @PostMapping("/send")
    public ResponseEntity<ApiResponse<String>> sendTestSms(
            @RequestParam("phone") String phone,
            @RequestParam("message") String message) {
        try {
            logger.info("=== SMS Test Request ===");
            logger.info("Phone: {}", phone);
            logger.info("Message: {}", message);
            
            if (phone == null || phone.trim().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Phone number is required"));
            }
            
            if (message == null || message.trim().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Message is required"));
            }
            
            // Send SMS
            messagingService.sendSms(message, phone);
            
            logger.info("SMS sent successfully to: {}", phone);
            return ResponseEntity.ok(ApiResponse.success("SMS sent successfully", 
                "SMS sent to " + phone));
                
        } catch (Exception e) {
            logger.error("Error sending test SMS: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Failed to send SMS: " + e.getMessage()));
        }
    }

    /**
     * Test endpoint to send SMS (JSON body version)
     * POST /api/test/sms/send-json
     * 
     * Request body:
     * {
     *   "phone": "250784638201",
     *   "message": "Test SMS message"
     * }
     */
    @PostMapping("/send-json")
    public ResponseEntity<ApiResponse<String>> sendTestSmsJson(
            @RequestBody SmsTestRequest request) {
        try {
            logger.info("=== SMS Test Request (JSON) ===");
            logger.info("Phone: {}", request.getPhone());
            logger.info("Message: {}", request.getMessage());
            
            if (request.getPhone() == null || request.getPhone().trim().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Phone number is required"));
            }
            
            if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Message is required"));
            }
            
            // Send SMS
            messagingService.sendSms(request.getMessage(), request.getPhone());
            
            logger.info("SMS sent successfully to: {}", request.getPhone());
            return ResponseEntity.ok(ApiResponse.success("SMS sent successfully", 
                "SMS sent to " + request.getPhone()));
                
        } catch (Exception e) {
            logger.error("Error sending test SMS: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Failed to send SMS: " + e.getMessage()));
        }
    }

    /**
     * Simple DTO for SMS test request
     */
    public static class SmsTestRequest {
        private String phone;
        private String message;

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}

