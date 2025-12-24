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
public class AdminDashboardStatisticsResponse {
    private Long totalUsers;
    private Long totalMerchants;
    private Long totalTransactions;
    private BigDecimal totalRevenue; // Total admin income
    private List<RecentActivity> recentActivities;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentActivity {
        private UUID id;
        private String type; // "PAYMENT", "TOP_UP", "BALANCE_ASSIGNMENT", etc.
        private String description;
        private BigDecimal amount;
        private LocalDateTime createdAt;
        private String status;
        
        // For transactions
        private String userName;
        private String receiverName;
        private String paymentCategoryName;
        
        // For balance assignments
        private String receiverCompanyName;
        private BigDecimal assignedBalance;
    }
}

