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
@Table(name = "efashe_refund_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EfasheRefundHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "efashe_transaction_id", nullable = false)
    private EfasheTransaction efasheTransaction;

    @Column(name = "original_transaction_id", nullable = false, length = 255)
    private String originalTransactionId; // EFASHE transaction ID

    @Column(name = "refund_transaction_id", nullable = false, unique = true, length = 255)
    private String refundTransactionId; // MoPay refund transaction ID

    @Column(name = "refund_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal refundAmount; // Amount refunded (transaction amount minus cashback)

    @Column(name = "admin_phone", nullable = false, length = 20)
    private String adminPhone; // Admin phone number (DEBIT - who pays for refund)

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone; // Receiver phone number (CREDIT - who receives refund)

    @Column(name = "message", length = 1000)
    private String message; // Optional message

    @Column(name = "status", nullable = false, length = 50)
    private String status = "PENDING"; // PENDING, SUCCESS, FAILED

    @Column(name = "mopay_status", length = 50)
    private String mopayStatus; // MoPay status from check-status endpoint

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
