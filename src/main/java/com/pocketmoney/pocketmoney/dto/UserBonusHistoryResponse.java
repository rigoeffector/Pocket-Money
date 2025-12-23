package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserBonusHistoryResponse {
    private UUID transactionId;
    private UUID userId;
    private String userFullNames;
    private UUID receiverId;
    private String receiverCompanyName;
    private UUID paymentCategoryId;
    private String paymentCategoryName;
    private BigDecimal paymentAmount;
    private BigDecimal bonusAmount;
    private LocalDateTime transactionDate;
}

