package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateRraRangeSettingRequest {

    @NotNull(message = "Min amount is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "Min amount must be >= 0")
    private BigDecimal minAmount;

    @DecimalMin(value = "0.0", inclusive = false, message = "Max amount must be > 0 if provided")
    private BigDecimal maxAmount; // Optional: null means no upper limit

    @NotNull(message = "Percentage is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "Percentage must be >= 0")
    private BigDecimal percentage; // 0-100

    @NotNull(message = "Priority is required")
    private Integer priority; // Lower number = higher priority

    private Boolean isActive = true;

    private String description;
}
