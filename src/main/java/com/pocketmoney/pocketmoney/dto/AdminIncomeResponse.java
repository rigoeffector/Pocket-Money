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
public class AdminIncomeResponse {
    private BigDecimal totalIncome;
    private Long totalTransactions;
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    
    // Total assigned balance to receivers (sum of approved balance assignments)
    private BigDecimal totalAssignedBalance;
    
    // Summary breakdown by receiver (for income)
    private List<IncomeBreakdown> breakdown;
    
    // Breakdown by receiver for assigned balances
    private List<AssignedBalanceBreakdown> assignedBalanceBreakdown;
    
    // Detailed transaction list
    private List<AdminIncomeTransaction> transactions;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IncomeBreakdown {
        private UUID receiverId;
        private String receiverCompanyName;
        private BigDecimal income;
        private Long transactionCount;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssignedBalanceBreakdown {
        private UUID receiverId;
        private String receiverCompanyName;
        private BigDecimal assignedBalance;
        private Long assignmentCount;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdminIncomeTransaction {
        private UUID transactionId;
        private LocalDateTime transactionDate;
        private UUID userId;
        private String userFullNames;
        private String userPhoneNumber;
        private UUID receiverId;
        private String receiverCompanyName;
        private UUID paymentCategoryId;
        private String paymentCategoryName;
        private BigDecimal paymentAmount;
        private BigDecimal discountAmount;
        private BigDecimal userBonusAmount;
        private BigDecimal adminIncomeAmount;
        private String status;
    }
}

