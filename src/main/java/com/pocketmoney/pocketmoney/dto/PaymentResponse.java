package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.TransactionStatus;
import com.pocketmoney.pocketmoney.entity.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private UUID id;
    private UUID userId;
    private UserResponse user; // Full user information
    private PaymentCategoryResponse paymentCategory; // Category information
    private TransactionType transactionType;
    private BigDecimal amount;
    private String mopayTransactionId;
    private TransactionStatus status;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private LocalDateTime createdAt;
}

