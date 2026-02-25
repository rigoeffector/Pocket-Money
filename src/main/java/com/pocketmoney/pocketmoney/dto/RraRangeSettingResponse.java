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
public class RraRangeSettingResponse {
    private UUID id;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private BigDecimal percentage;
    private Boolean isActive;
    private Integer priority;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
