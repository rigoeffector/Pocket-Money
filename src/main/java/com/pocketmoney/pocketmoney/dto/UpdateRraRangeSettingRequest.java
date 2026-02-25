package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRraRangeSettingRequest {

    @DecimalMin(value = "0.0", inclusive = true, message = "Min amount must be >= 0")
    private BigDecimal minAmount;

    @DecimalMin(value = "0.0", inclusive = false, message = "Max amount must be > 0 if provided")
    private BigDecimal maxAmount; // Optional: null means no upper limit

    @DecimalMin(value = "0.0", inclusive = true, message = "Percentage must be >= 0")
    private BigDecimal percentage; // 0-100

    private Integer priority; // Lower number = higher priority

    private Boolean isActive;

    private String description;
}
