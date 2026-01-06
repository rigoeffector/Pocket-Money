package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.LoanStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoanResponse {
    private UUID id;
    private UUID userId;
    private String userFullNames;
    private String userPhoneNumber;
    private UUID receiverId;
    private String receiverCompanyName;
    private UUID transactionId;
    private BigDecimal loanAmount;
    private BigDecimal paidAmount;
    private BigDecimal remainingAmount;
    private LoanStatus status;
    private LocalDateTime paidAt;
    private LocalDateTime lastPaymentAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

