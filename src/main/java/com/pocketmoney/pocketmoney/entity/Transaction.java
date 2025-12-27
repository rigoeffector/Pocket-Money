package com.pocketmoney.pocketmoney.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true)
    private User user; // Nullable for guest MOMO payments

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_category_id")
    private PaymentCategory paymentCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id")
    private Receiver receiver;

    @Column(name = "transaction_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private TransactionType transactionType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "mopay_transaction_id", length = 500)
    private String mopayTransactionId;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "phone_number", length = 50)
    private String phoneNumber;

    @Column(name = "message", length = 1000)
    private String message;

    @Column(name = "balance_before", precision = 19, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "discount_amount", precision = 19, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "user_bonus_amount", precision = 19, scale = 2)
    private BigDecimal userBonusAmount;

    @Column(name = "admin_income_amount", precision = 19, scale = 2)
    private BigDecimal adminIncomeAmount;

    @Column(name = "receiver_balance_before", precision = 19, scale = 2)
    private BigDecimal receiverBalanceBefore;

    @Column(name = "receiver_balance_after", precision = 19, scale = 2)
    private BigDecimal receiverBalanceAfter;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(nullable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

