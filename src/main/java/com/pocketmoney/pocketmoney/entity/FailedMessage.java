package com.pocketmoney.pocketmoney.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "failed_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FailedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "message_type", nullable = false, length = 20)
    private String messageType; // "SMS" or "WHATSAPP"

    @Column(name = "phone_number", nullable = false, length = 50)
    private String phoneNumber;

    @Column(name = "message", nullable = false, length = 2000)
    private String message;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING"; // PENDING, RESENT, RESENT_SUCCESS, RESENT_FAILED

    @Column(name = "last_retry_at")
    private LocalDateTime lastRetryAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
