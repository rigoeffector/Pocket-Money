package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.TopUpType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MerchantTopUpRequest {
    @NotBlank(message = "Phone number is required")
    private String phone; // User's phone number (linked to their card)

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    private BigDecimal amount;

    @NotNull(message = "Top-up type is required")
    private TopUpType topUpType; // MOMO, CASH, or LOAN

    private String message; // Optional message/notes
}

