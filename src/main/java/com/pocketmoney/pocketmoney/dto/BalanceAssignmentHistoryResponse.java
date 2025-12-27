package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.BalanceAssignmentStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceAssignmentHistoryResponse {
    private UUID id;
    private UUID receiverId;
    private String receiverCompanyName;
    private BigDecimal assignedBalance;
    private BigDecimal previousAssignedBalance;
    private BigDecimal balanceDifference;
    private BigDecimal paymentAmount; // Amount being paid (always equals assignedBalance, regardless of balanceDifference)
    private String assignedBy;
    private String notes;
    private BalanceAssignmentStatus status;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private String mopayTransactionId; // MoPay transaction ID
    private LocalDateTime createdAt;
}

