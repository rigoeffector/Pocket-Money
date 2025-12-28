package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GlobalSettingsResponse {
    private UUID id;
    private BigDecimal adminDiscountPercentage;
    private BigDecimal userBonusPercentage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

