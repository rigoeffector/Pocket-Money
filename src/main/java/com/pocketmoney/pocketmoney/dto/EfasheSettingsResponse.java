package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EfasheSettingsResponse {
    private UUID id;
    private EfasheServiceType serviceType;
    private String fullAmountPhoneNumber;
    private String cashbackPhoneNumber;
    private BigDecimal agentCommissionPercentage;
    private BigDecimal customerCashbackPercentage;
    private BigDecimal besoftSharePercentage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

