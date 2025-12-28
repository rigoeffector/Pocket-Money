package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiverWalletResponse {
    private UUID receiverId;
    private String companyName;
    private String receiverPhone;
    private BigDecimal walletBalance;
    private BigDecimal totalReceived;
    private BigDecimal assignedBalance;
    private BigDecimal remainingBalance;
    private BigDecimal discountPercentage;
    private BigDecimal userBonusPercentage;
    private List<CommissionInfo> commissionSettings; // Commission phone numbers and percentages
    private LocalDateTime lastTransactionDate;
}

