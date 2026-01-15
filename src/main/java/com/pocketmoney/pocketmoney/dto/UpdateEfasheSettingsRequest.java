package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateEfasheSettingsRequest {
    @NotBlank(message = "Full amount phone number is required")
    private String fullAmountPhoneNumber;

    @NotBlank(message = "Cashback phone number is required")
    private String cashbackPhoneNumber;

    @NotNull(message = "Agent commission percentage is required")
    private BigDecimal agentCommissionPercentage;

    @NotNull(message = "Customer cashback percentage is required")
    private BigDecimal customerCashbackPercentage;

    @NotNull(message = "Besoft share percentage is required")
    private BigDecimal besoftSharePercentage;
}

