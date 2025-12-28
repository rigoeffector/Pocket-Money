package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateGlobalSettingsRequest {
    
    @DecimalMin(value = "0", message = "Admin discount percentage must be greater than or equal to 0")
    @DecimalMax(value = "100", message = "Admin discount percentage must be less than or equal to 100")
    private BigDecimal adminDiscountPercentage;

    @DecimalMin(value = "0", message = "User bonus percentage must be greater than or equal to 0")
    @DecimalMax(value = "100", message = "User bonus percentage must be less than or equal to 100")
    private BigDecimal userBonusPercentage;
}

