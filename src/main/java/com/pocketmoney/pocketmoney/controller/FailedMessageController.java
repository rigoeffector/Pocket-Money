package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.FailedMessageResponse;
import com.pocketmoney.pocketmoney.dto.PaginatedResponse;
import com.pocketmoney.pocketmoney.dto.ResendMessageRequest;
import com.pocketmoney.pocketmoney.dto.ResendMessageResponse;
import com.pocketmoney.pocketmoney.service.FailedMessageService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/failed-messages")
public class FailedMessageController {

    private static final Logger logger = LoggerFactory.getLogger(FailedMessageController.class);

    private final FailedMessageService failedMessageService;

    public FailedMessageController(FailedMessageService failedMessageService) {
        this.failedMessageService = failedMessageService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PaginatedResponse<FailedMessageResponse>>> getFailedMessages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String messageType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        try {
            PaginatedResponse<FailedMessageResponse> response = failedMessageService.getFailedMessages(
                    page, size, messageType, status, search);
            return ResponseEntity.ok(ApiResponse.success("Failed messages retrieved successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error fetching failed messages: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error fetching failed messages: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to fetch failed messages: " + e.getMessage()));
        }
    }

    @PostMapping("/resend")
    public ResponseEntity<ApiResponse<ResendMessageResponse>> resendFailedMessages(
            @Valid @RequestBody ResendMessageRequest request) {
        try {
            logger.info("Received resend request for {} failed messages", request.getFailedMessageIds().size());
            
            ResendMessageResponse response = failedMessageService.resendFailedMessages(
                    request.getFailedMessageIds());

            String message = String.format(
                    "Resend completed. Total: %d, Success: %d, Failed: %d",
                    response.getTotalRequested(),
                    response.getSuccessCount(),
                    response.getFailureCount()
            );

            return ResponseEntity.ok(ApiResponse.success(message, response));
        } catch (RuntimeException e) {
            logger.error("Error resending failed messages: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error resending failed messages: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to resend messages: " + e.getMessage()));
        }
    }
}
