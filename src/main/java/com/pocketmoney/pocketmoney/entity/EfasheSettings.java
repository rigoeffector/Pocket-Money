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
@Table(name = "efashe_settings", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"service_type"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EfasheSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, unique = true)
    private EfasheServiceType serviceType;

    @Column(name = "full_amount_phone_number", nullable = false)
    private String fullAmountPhoneNumber;

    @Column(name = "cashback_phone_number", nullable = false)
    private String cashbackPhoneNumber;

    @Column(name = "agent_commission_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal agentCommissionPercentage = BigDecimal.ZERO;

    @Column(name = "customer_cashback_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal customerCashbackPercentage = BigDecimal.ZERO;

    @Column(name = "besoft_share_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal besoftSharePercentage = BigDecimal.ZERO;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(nullable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

