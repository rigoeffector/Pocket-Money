package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceResponse {
    private UUID userId;
    private String fullNames;
    private String phoneNumber;
    private String email;
    private Boolean isAssignedNfcCard;
    private String nfcCardId;
    private BigDecimal amountOnCard;
    private BigDecimal amountRemaining; // Global balance
    private BigDecimal totalBonusReceived;
    private BigDecimal amountRemainingWithBonus; // amountRemaining + totalBonusReceived
    private List<MerchantBalanceInfo> merchantBalances; // Merchant-specific balances
    private UserStatus status;
    private LocalDateTime lastTransactionDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

