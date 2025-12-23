package com.pocketmoney.pocketmoney.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "balance_assignment_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceAssignmentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private Receiver receiver;

    @Column(name = "assigned_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal assignedBalance;

    @Column(name = "previous_assigned_balance", precision = 19, scale = 2)
    private BigDecimal previousAssignedBalance;

    @Column(name = "balance_difference", precision = 19, scale = 2)
    private BigDecimal balanceDifference; // New balance - Previous balance

    @Column(name = "assigned_by", length = 255)
    private String assignedBy; // Username or identifier of who assigned the balance

    @Column(name = "notes", length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BalanceAssignmentStatus status = BalanceAssignmentStatus.PENDING;

    @Column(name = "approved_by", length = 255)
    private String approvedBy; // Username or identifier of who approved/rejected

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;
}

