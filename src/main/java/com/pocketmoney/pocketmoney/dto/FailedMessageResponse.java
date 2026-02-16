package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FailedMessageResponse {
    private UUID id;
    private String messageType; // "SMS" or "WHATSAPP"
    private String phoneNumber;
    private String message;
    private String errorMessage;
    private Integer retryCount;
    private String status; // PENDING, RESENT, RESENT_SUCCESS, RESENT_FAILED
    private LocalDateTime lastRetryAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
