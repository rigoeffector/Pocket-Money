package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateUserRequest {
    private String fullNames;

    @Pattern(regexp = "^[0-9]{10,15}$", message = "Phone number must be between 10 and 15 digits")
    private String phoneNumber;

    @Email(message = "Email should be valid")
    private String email;

    private Boolean isAssignedNfcCard;
    private String nfcCardId;
    private BigDecimal amountOnCard;
    private BigDecimal amountRemaining;
    private String status;
}

