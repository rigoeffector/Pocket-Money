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
@Table(name = "payment_commission_settings", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"receiver_id", "phone_number"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCommissionSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private Receiver receiver;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber; // Phone number that will receive commission

    @Column(name = "commission_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal commissionPercentage; // Percentage (0-100) of payment amount that goes to this phone number

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true; // Whether this setting is active

    @Column(name = "description", length = 500)
    private String description; // Optional description

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(nullable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

