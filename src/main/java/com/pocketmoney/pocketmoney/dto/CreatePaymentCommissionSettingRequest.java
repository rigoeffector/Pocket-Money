package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class CreatePaymentCommissionSettingRequest {
    
    @NotNull(message = "Receiver ID is required")
    private UUID receiverId;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[0-9]{10,15}$", message = "Phone number must be between 10 and 15 digits")
    private String phoneNumber;

    @NotNull(message = "Commission percentage is required")
    @DecimalMin(value = "0", message = "Commission percentage must be greater than or equal to 0")
    @DecimalMax(value = "100", message = "Commission percentage must be less than or equal to 100")
    private BigDecimal commissionPercentage;

    private String description; // Optional
}

