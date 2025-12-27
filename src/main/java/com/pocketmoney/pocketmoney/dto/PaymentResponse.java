package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.TransactionStatus;
import com.pocketmoney.pocketmoney.entity.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    private Boolean isSubmerchant; // true if transaction was made by a submerchant
    // Payment method information
    private String payerPhone; // Phone number used for payment (if MOMO payment)
    private String paymentMethod; // "MOMO" or "NFC_CARD"
}

