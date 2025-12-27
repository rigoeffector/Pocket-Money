package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiverTransactionsResponse {
    private List<PaymentResponse> transactions;
    private TransactionStatistics statistics;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionStatistics {
        private Long totalTransactions;
        private BigDecimal totalRevenue; // Sum of all successful payment amounts
        private Long totalCustomers; // Distinct users who made payments
        private BigDecimal totalDiscountAmount; // Sum of all discount amounts
        private BigDecimal totalUserBonusAmount; // Sum of all user bonus amounts
        private BigDecimal totalAdminIncomeAmount; // Sum of all admin income amounts
        private Long successfulTransactions; // Count of successful transactions
        private Long pendingTransactions; // Count of pending transactions
        private Long failedTransactions; // Count of failed transactions
    }
}

