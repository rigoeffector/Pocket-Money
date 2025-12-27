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
@Table(name = "receivers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Receiver {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "manager_name", nullable = false)
    private String managerName;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "password", nullable = false)
    private String password; // This will be hashed

    @Column(name = "receiver_phone", nullable = false, unique = true)
    private String receiverPhone;

    @Column(name = "account_number")
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReceiverStatus status = ReceiverStatus.NOT_ACTIVE;

    @Column(name = "email")
    private String email;

    @Column(name = "address")
    private String address;

    @Column(name = "description")
    private String description;

    @Column(name = "wallet_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal walletBalance = BigDecimal.ZERO;

    @Column(name = "total_received", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalReceived = BigDecimal.ZERO;

    @Column(name = "assigned_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal assignedBalance = BigDecimal.ZERO;

    @Column(name = "remaining_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingBalance = BigDecimal.ZERO;

    @Column(name = "discount_percentage", precision = 5, scale = 2)
    private BigDecimal discountPercentage = BigDecimal.ZERO;

    @Column(name = "user_bonus_percentage", precision = 5, scale = 2)
    private BigDecimal userBonusPercentage = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_receiver_id")
    private Receiver parentReceiver; // For submerchant relationships

    @Column(name = "last_transaction_date")
    private LocalDateTime lastTransactionDate;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(nullable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

