package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.BalanceAssignmentStatus;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import com.pocketmoney.pocketmoney.entity.TransactionStatus;
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
public class ReceiverDashboardResponse {
    // Basic receiver info
    private UUID receiverId;
    private String companyName;
    private String managerName;
    private String receiverPhone;
    private String email;
    private String address;
    
    // Wallet information
    private BigDecimal walletBalance;
    private BigDecimal totalReceived;
    private BigDecimal assignedBalance;
    private BigDecimal remainingBalance;
    private BigDecimal discountPercentage;
    private BigDecimal userBonusPercentage;
    private Integer pendingBalanceAssignments;
    
    // Transaction statistics (for this receiver only)
    private Long totalTransactions;
    private BigDecimal totalRevenue; // Total amount received from payments
    private Long totalCustomers; // Distinct users who made payments
    
    // Submerchant info (if main merchant)
    private Boolean isMainMerchant; // true if has submerchants, false if is submerchant
    private UUID parentReceiverId; // null if main merchant, set if submerchant
    private String parentReceiverCompanyName;
    private List<SubmerchantInfo> submerchants; // Only populated if main merchant
    
    // Full statistics (only for main merchant, includes all submerchants)
    private FullStatistics fullStatistics; // Only populated if main merchant
    
    // Recent transactions (5 most recent)
    private List<RecentTransaction> recentTransactions;
    
    // Balance assignment history summary
    private List<BalanceAssignmentSummary> recentBalanceAssignments;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmerchantInfo {
        private UUID submerchantId;
        private String companyName;
        private String managerName;
        private String receiverPhone;
        private BigDecimal walletBalance;
        private BigDecimal totalReceived;
        private BigDecimal remainingBalance;
        private Long totalTransactions;
        private BigDecimal totalRevenue;
        private ReceiverStatus status;
        private LocalDateTime createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FullStatistics {
        private Long totalTransactions; // All transactions from main merchant + submerchants
        private BigDecimal totalRevenue; // All revenue from main merchant + submerchants
        private Long totalCustomers; // Distinct customers across all merchants
        private Long totalSubmerchants;
        private BigDecimal combinedWalletBalance; // Sum of all wallet balances
        private BigDecimal combinedRemainingBalance; // Sum of all remaining balances
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentTransaction {
        private UUID transactionId;
        private UUID userId;
        private String userName;
        private String userPhone;
        private BigDecimal amount;
        private BigDecimal discountAmount;
        private BigDecimal userBonusAmount;
        private TransactionStatus status;
        private String paymentCategoryName;
        private LocalDateTime createdAt;
        // Receiver information (to identify which receiver/submerchant made this transaction)
        private UUID receiverId;
        private String receiverCompanyName;
        private Boolean isSubmerchant; // true if transaction was made by a submerchant
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceAssignmentSummary {
        private UUID historyId;
        private BigDecimal assignedBalance;
        private BigDecimal balanceDifference;
        private BalanceAssignmentStatus status;
        private String notes;
        private LocalDateTime createdAt;
        private LocalDateTime approvedAt;
        private String approvedBy;
    }
}

