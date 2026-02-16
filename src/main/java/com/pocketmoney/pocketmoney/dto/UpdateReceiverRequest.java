package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

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

    private String country;

    private String countryCode;

    private String description;

    private java.math.BigDecimal assignedBalance;

    private java.math.BigDecimal discountPercentage;

    private java.math.BigDecimal userBonusPercentage;

    @jakarta.validation.constraints.DecimalMin(value = "0.00", message = "Commission percentage must be 0 or greater")
    @jakarta.validation.constraints.DecimalMax(value = "100.00", message = "Commission percentage must be 100 or less")
    private java.math.BigDecimal commissionPercentage; // Optional: single commission (legacy) - use commissionSettings for array shape

    @Pattern(regexp = "^[0-9]{10,15}$", message = "Commission phone number must be between 10 and 15 digits")
    private String commissionPhoneNumber; // Optional: single commission phone (legacy)

    /** Commission list as sent by frontend (same shape as response commissionSettings). When present, replaces receiver's commission settings. */
    private List<CommissionInfo> commissionSettings;

    @Pattern(regexp = "^[0-9]{10,15}$", message = "Admin phone number must be between 10 and 15 digits")
    private String adminPhone; // Admin phone number for MoPay payment (DEBIT)

    @Pattern(regexp = "^[0-9]{10,15}$", message = "MoMo account phone number must be between 10 and 15 digits")
    private String momoAccountPhone; // MoMo account phone for receiving top-up payments

    private String momoCode; // MoMo merchant code

    private Boolean isFlexible; // If true, users can pay without checking receiver's remaining balance
}

