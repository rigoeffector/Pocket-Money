package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiverAnalyticsResponse {
    private UUID receiverId;
    private String companyName;
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    
    // Analytics metrics
    private BigDecimal totalPaid; // Total amount received
    private Long totalTransactions; // Total invoice sent (total transactions)
    private Long approvedTransactions; // Successful transactions
    private Long totalUsers; // Unique users who made payments to this merchant
    private BigDecimal averageTransactionAmount; // Average transaction amount
    
    // Breakdown by category (optional)
    private Map<UUID, CategoryAnalytics> categoryBreakdown;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryAnalytics {
        private UUID categoryId;
        private String categoryName;
        private Long transactionCount;
        private BigDecimal totalAmount;
    }
}

