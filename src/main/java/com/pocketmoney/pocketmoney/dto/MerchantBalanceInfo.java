package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MerchantBalanceInfo {
    private UUID receiverId;
    private String receiverCompanyName;
    private BigDecimal balance;
    private BigDecimal totalToppedUp;
}

