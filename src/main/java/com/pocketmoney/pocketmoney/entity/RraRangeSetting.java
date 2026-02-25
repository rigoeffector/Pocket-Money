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
@Table(name = "rra_range_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RraRangeSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "min_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal minAmount; // Minimum amount for this range (inclusive)

    @Column(name = "max_amount", precision = 15, scale = 2)
    private BigDecimal maxAmount; // Maximum amount for this range (exclusive, null means no upper limit)

    @Column(name = "percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal percentage; // Percentage (0-100) to apply for amounts in this range

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true; // Whether this range setting is active

    @Column(name = "priority", nullable = false)
    private Integer priority; // Lower number = higher priority (checked first)

    @Column(name = "description", length = 500)
    private String description; // Optional description for this range

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(nullable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
