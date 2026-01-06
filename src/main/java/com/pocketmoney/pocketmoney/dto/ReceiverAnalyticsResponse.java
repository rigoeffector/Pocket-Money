package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
    
    // Recent transactions (5 most recent)
    private List<RecentTransaction> recentTransactions;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryAnalytics {
        private UUID categoryId;
        private String categoryName;
        private Long transactionCount;
        private BigDecimal totalAmount;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentTransaction {
        private UUID transactionId;
        private String mopayTransactionId; // POCHI transaction ID for tracking
        private UUID userId;
        private String userName;
        private String userPhone;
        private BigDecimal amount;
        private BigDecimal discountAmount;
        private BigDecimal userBonusAmount;
        private String status;
        private String paymentCategoryName;
        private LocalDateTime createdAt;
        private UUID receiverId;
        private String receiverCompanyName;
        private Boolean isSubmerchant;
    }
}

