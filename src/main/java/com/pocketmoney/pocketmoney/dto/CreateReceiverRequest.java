package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreateReceiverRequest {
    @NotBlank(message = "Company name is required")
    private String companyName;

    @NotBlank(message = "Manager name is required")
    private String managerName;

    @NotBlank(message = "Username is required")
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,20}$", message = "Username must be 3-20 characters and contain only letters, numbers, and underscores")
    private String username;

    @NotBlank(message = "Password is required")
    @Pattern(regexp = "^.{6,}$", message = "Password must be at least 6 characters")
    private String password;

    @NotBlank(message = "Receiver phone number is required")
    @Pattern(regexp = "^[0-9]{10,15}$", message = "Phone number must be between 10 and 15 digits")
    private String receiverPhone;

    private String accountNumber;

    @NotNull(message = "Status is required")
    private ReceiverStatus status;

    // Email is optional - validation is done in service layer
    private String email;

    private String address;

    private String description;
}

