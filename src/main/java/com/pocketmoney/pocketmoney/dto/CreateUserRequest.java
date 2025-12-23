package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateUserRequest {
    @NotBlank(message = "Full names are required")
    private String fullNames;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Phone number must be between 10 and 15 digits (may include + prefix)")
    private String phoneNumber;

    // Email is optional - validation is done in service layer
    private String email;

    @NotBlank(message = "PIN is required")
    @Pattern(regexp = "^[0-9]{4}$", message = "PIN must be exactly 4 digits")
    private String pin;

    private String nfcCardId;
    private BigDecimal initialAmount = BigDecimal.ZERO;
}

