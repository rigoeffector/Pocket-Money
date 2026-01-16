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
@Table(name = "efashe_transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EfasheTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private String transactionId; // EFASHE transaction ID

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false)
    private EfasheServiceType serviceType;

    @Column(name = "customer_phone", nullable = false)
    private String customerPhone;

    @Column(name = "customer_account_number", nullable = false)
    private String customerAccountNumber;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency = "RWF";

    @Column(name = "trx_id")
    private String trxId; // EFASHE trxId from validate response

    @Column(name = "mopay_transaction_id")
    private String mopayTransactionId; // MoPay transaction ID

    @Column(name = "mopay_status")
    private String mopayStatus; // Current MoPay status: PENDING, SUCCESS, FAILED

    @Column(name = "efashe_status")
    private String efasheStatus; // Current EFASHE status: PENDING, SUCCESS, FAILED

    @Column(name = "initial_mopay_status")
    private String initialMopayStatus; // Initial MoPay status when transaction was created

    @Column(name = "initial_efashe_status")
    private String initialEfasheStatus; // Initial EFASHE status when transaction was created

    @Column(name = "delivery_method_id")
    private String deliveryMethodId; // print, email, sms, direct_topup

    @Column(name = "deliver_to")
    private String deliverTo;

    @Column(name = "poll_endpoint")
    private String pollEndpoint; // EFASHE poll endpoint for async status checking

    @Column(name = "retry_after_secs")
    private Integer retryAfterSecs; // Retry interval for polling

    @Column(name = "message", length = 1000)
    private String message;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "customer_cashback_amount", precision = 10, scale = 2)
    private BigDecimal customerCashbackAmount; // Amount to send to customer after execute

    @Column(name = "besoft_share_amount", precision = 10, scale = 2)
    private BigDecimal besoftShareAmount; // Amount to send to besoft phone after execute

    @Column(name = "full_amount_phone")
    private String fullAmountPhone; // Phone number that will send cashback transfers

    @Column(name = "cashback_phone")
    private String cashbackPhone; // Besoft phone number

    @Column(name = "cashback_sent")
    private Boolean cashbackSent = false; // Flag to track if cashback transfers were sent

    @Column(name = "full_amount_transaction_id")
    private String fullAmountTransactionId; // Unique transaction ID for full amount transfer

    @Column(name = "customer_cashback_transaction_id")
    private String customerCashbackTransactionId; // Unique transaction ID for customer cashback transfer

    @Column(name = "besoft_share_transaction_id")
    private String besoftShareTransactionId; // Unique transaction ID for besoft share transfer

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

