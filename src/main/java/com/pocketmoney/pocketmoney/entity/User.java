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
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "full_names", nullable = false)
    private String fullNames;

    @Column(name = "phone_number", nullable = false, unique = true)
    private String phoneNumber;

    @Column(name = "email")
    private String email;

    @Column(name = "is_assigned_nfc_card", nullable = false)
    private Boolean isAssignedNfcCard = false;

    @Column(name = "nfc_card_id", unique = true)
    private String nfcCardId;

    @Column(name = "amount_on_card", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountOnCard = BigDecimal.ZERO;

    @Column(name = "amount_remaining", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountRemaining = BigDecimal.ZERO;

    @Column(name = "pin", nullable = false)
    private String pin; // Will be hashed

    @Column(name = "otp")
    private String otp;

    @Column(name = "otp_expires_at")
    private LocalDateTime otpExpiresAt;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "last_transaction_date")
    private LocalDateTime lastTransactionDate;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(nullable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

