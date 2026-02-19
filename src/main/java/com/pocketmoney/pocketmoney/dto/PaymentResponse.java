package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pocketmoney.pocketmoney.entity.TransactionStatus;
import com.pocketmoney.pocketmoney.entity.TransactionType;
import com.pocketmoney.pocketmoney.entity.TopUpType;
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
public class PaymentResponse {
    private UUID id;
    private UUID userId;
    private UserResponse user; // Full user information
    private PaymentCategoryResponse paymentCategory; // Category information
    private TransactionType transactionType;
    private TopUpType topUpType; // LOAN, MOMO, or CASH (only for TOP_UP transactions)
    private BigDecimal amount;
    private String mopayTransactionId;
    private TransactionStatus status;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private BigDecimal discountAmount;
    private BigDecimal userBonusAmount;
    private BigDecimal adminIncomeAmount;
    private BigDecimal receiverBalanceBefore;
    private BigDecimal receiverBalanceAfter;
    private LocalDateTime createdAt;
    // Receiver information (to identify which receiver/submerchant made this transaction)
    private UUID receiverId;
    private String receiverCompanyName;
    private String momoCode; // MoMo merchant code (if configured)
    private Boolean isSubmerchant; // true if transaction was made by a submerchant
    // Payment method information
    private String payerPhone; // Phone number used for payment (if MOMO payment)
    private String paymentMethod; // "MOMO" or "NFC_CARD"
    // Commission information
    private List<CommissionInfo> commissionSettings; // Commission phone numbers and percentages for this receiver
    
    // Loan information (only for LOAN top-up transactions)
    private UUID loanId; // Loan ID if this is a LOAN transaction
    private LoanInfo loanInfo; // Loan details if this is a LOAN transaction
    
    // Transfer recipient information (for PAY_CUSTOMER transactions)
    @JsonInclude(JsonInclude.Include.ALWAYS) // Always include, even if null or empty
    private List<TransferRecipientInfo> transferRecipients; // List of transfer recipients with their names
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferRecipientInfo {
        private String phone; // Recipient phone number
        private String name; // Recipient name from account holder information
        private BigDecimal amount; // Transfer amount
    }
}

