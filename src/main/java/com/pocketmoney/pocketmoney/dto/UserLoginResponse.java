package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginResponse {
    private String token;
    private String tokenType = "Bearer";
    private String userType = "USER";
    private UUID id;
    private String fullNames;
    private String phoneNumber;
    private String email;
    private Boolean isAssignedNfcCard;
    private String nfcCardId;
    private BigDecimal amountOnCard;
    private BigDecimal amountRemaining;
    private UserStatus status;
    private LocalDateTime lastTransactionDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

