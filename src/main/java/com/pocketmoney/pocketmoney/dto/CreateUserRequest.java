package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Email;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateUserRequest {
    @NotBlank(message = "Full names are required")
    private String fullNames;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[0-9]{10,15}$", message = "Phone number must be between 10 and 15 digits")
    private String phoneNumber;

    @Email(message = "Email should be valid")
    private String email;

    @NotBlank(message = "PIN is required")
    @Pattern(regexp = "^[0-9]{4,6}$", message = "PIN must be between 4 and 6 digits")
    private String pin;

    private String nfcCardId;
    private BigDecimal initialAmount = BigDecimal.ZERO;
}

