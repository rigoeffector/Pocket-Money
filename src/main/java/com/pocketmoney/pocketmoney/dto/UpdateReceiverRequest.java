package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateReceiverRequest {
    private String companyName;

    private String managerName;

    @Pattern(regexp = "^[a-zA-Z0-9_]{3,20}$", message = "Username must be 3-20 characters and contain only letters, numbers, and underscores")
    private String username;

    @Pattern(regexp = "^.{6,}$", message = "Password must be at least 6 characters")
    private String password;

    @Pattern(regexp = "^[0-9]{10,15}$", message = "Phone number must be between 10 and 15 digits")
    private String receiverPhone;

    private String accountNumber;

    private ReceiverStatus status;

    @Email(message = "Email should be valid")
    private String email;

    private String address;

    private String description;

    private java.math.BigDecimal assignedBalance;

    private java.math.BigDecimal discountPercentage;

    private java.math.BigDecimal userBonusPercentage;
}

