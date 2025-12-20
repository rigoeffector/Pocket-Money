package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiverResponse {
    private UUID id;
    private String companyName;
    private String managerName;
    private String username;
    private String receiverPhone;
    private String accountNumber;
    private ReceiverStatus status;
    private String email;
    private String address;
    private String description;
    private BigDecimal walletBalance;
    private BigDecimal totalReceived;
    private LocalDateTime lastTransactionDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

