package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.BulkSmsRequest;
import com.pocketmoney.pocketmoney.dto.BulkSmsResponse;
import com.pocketmoney.pocketmoney.dto.BulkWhatsAppRequest;
import com.pocketmoney.pocketmoney.dto.BulkWhatsAppResponse;
import com.pocketmoney.pocketmoney.service.MessagingService;
import com.pocketmoney.pocketmoney.service.WhatsAppService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/messaging")
public class MessagingController {

    private static final Logger logger = LoggerFactory.getLogger(MessagingController.class);

    private final MessagingService messagingService;
    private final WhatsAppService whatsAppService;

    public MessagingController(MessagingService messagingService, WhatsAppService whatsAppService) {
        this.messagingService = messagingService;
        this.whatsAppService = whatsAppService;
    }

    /**
     * Send bulk SMS to multiple recipients
     * POST /api/messaging/bulk-sms
     * 
     * Request body:
     * {
     *   "message": "Your message here",
     *   "phoneNumbers": ["250784638201", "250784638202", "250784638203"]
     * }
     * 
     * Response:
     * {
     *   "success": true,
     *   "message": "Bulk SMS sent successfully",
     *   "data": {
     *     "totalRecipients": 3,
     *     "successCount": 2,
     *     "failureCount": 1,
     *     "skippedCount": 0,
     *     "results": [
     *       {
     *         "phoneNumber": "250784638201",
     *         "success": true,
     *         "errorMessage": null
     *       },
     *       {
     *         "phoneNumber": "250784638202",
     *         "success": false,
     *         "errorMessage": "Error message here"
     *       }
     *     ]
     *   }
     * }
     */
    @PostMapping("/bulk-sms")
    public ResponseEntity<ApiResponse<BulkSmsResponse>> sendBulkSms(
            @Valid @RequestBody BulkSmsRequest request) {
        try {
            logger.info("Received bulk SMS request - Message length: {}, Recipients: {}", 
                request.getMessage() != null ? request.getMessage().length() : 0,
                request.getPhoneNumbers() != null ? request.getPhoneNumbers().size() : 0);
            
            BulkSmsResponse response = messagingService.sendBulkSmsWithResults(
                request.getMessage(), 
                request.getPhoneNumbers()
            );
            
            String message = String.format(
                "Bulk SMS sent. Total: %d, Success: %d, Failed: %d, Skipped: %d",
                response.getTotalRecipients(),
                response.getSuccessCount(),
                response.getFailureCount(),
                response.getSkippedCount()
            );
            
            return ResponseEntity.ok(ApiResponse.success(message, response));
        } catch (RuntimeException e) {
            logger.error("Error sending bulk SMS: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error sending bulk SMS: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to send bulk SMS: " + e.getMessage()));
        }
    }

    /**
     * Send bulk WhatsApp to multiple recipients
     * POST /api/messaging/bulk-whatsapp
     * 
     * Request body:
     * {
     *   "message": "Your message here",
     *   "phoneNumbers": ["250784638201", "250784638202", "250784638203"]
     * }
     * 
     * Response:
     * {
     *   "success": true,
     *   "message": "Bulk WhatsApp sent successfully",
     *   "data": {
     *     "totalRecipients": 3,
     *     "successCount": 2,
     *     "failureCount": 1,
     *     "skippedCount": 0,
     *     "results": [...]
     *   }
     * }
     */
    @PostMapping("/bulk-whatsapp")
    public ResponseEntity<ApiResponse<BulkWhatsAppResponse>> sendBulkWhatsApp(
            @Valid @RequestBody BulkWhatsAppRequest request) {
        try {
            logger.info("Received bulk WhatsApp request - Message length: {}, Recipients: {}", 
                request.getMessage() != null ? request.getMessage().length() : 0,
                request.getPhoneNumbers() != null ? request.getPhoneNumbers().size() : 0);
            
            BulkWhatsAppResponse response = whatsAppService.sendBulkWhatsAppWithResults(
                request.getMessage(), 
                request.getPhoneNumbers()
            );
            
            String message = String.format(
                "Bulk WhatsApp sent. Total: %d, Success: %d, Failed: %d, Skipped: %d",
                response.getTotalRecipients(),
                response.getSuccessCount(),
                response.getFailureCount(),
                response.getSkippedCount()
            );
            
            return ResponseEntity.ok(ApiResponse.success(message, response));
        } catch (RuntimeException e) {
            logger.error("Error sending bulk WhatsApp: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error sending bulk WhatsApp: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to send bulk WhatsApp: " + e.getMessage()));
        }
    }
}
