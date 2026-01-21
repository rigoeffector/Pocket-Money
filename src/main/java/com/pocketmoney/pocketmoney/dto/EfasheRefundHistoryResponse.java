package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EfasheRefundHistoryResponse {
    private UUID id;
    private UUID efasheTransactionId;
    private String originalTransactionId;
    private String refundTransactionId;
    private BigDecimal refundAmount;
    private String adminPhone;
    private String receiverPhone;
    private String message;
    private String status; // PENDING, SUCCESS, FAILED
    private String mopayStatus;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
