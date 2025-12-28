package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AssignBalanceRequest {
    @NotNull(message = "Assigned balance is required")
    @DecimalMin(value = "0.01", message = "Assigned balance must be greater than 0")
    private BigDecimal assignedBalance;

    @NotNull(message = "Admin phone number is required")
    @Pattern(regexp = "^[0-9]{10,15}$", message = "Admin phone number must be between 10 and 15 digits")
    private String adminPhone; // DEBIT - Admin's phone number for MoPay payment

    @NotNull(message = "Receiver phone number is required")
    @Pattern(regexp = "^[0-9]{10,15}$", message = "Receiver phone number must be between 10 and 15 digits")
    private String receiverPhone; // RECEIVER - Receiver's phone number for MoPay payment

    @DecimalMin(value = "0.00", message = "Discount percentage must be 0 or greater")
    @DecimalMax(value = "100.00", message = "Discount percentage must be 100 or less")
    private BigDecimal discountPercentage; // Optional: Set discount % (0-100)

    @DecimalMin(value = "0.00", message = "User bonus percentage must be 0 or greater")
    @DecimalMax(value = "100.00", message = "User bonus percentage must be 100 or less")
    private BigDecimal userBonusPercentage; // Optional: Set user bonus % (0-100)

    @DecimalMin(value = "0.00", message = "Commission percentage must be 0 or greater")
    @DecimalMax(value = "100.00", message = "Commission percentage must be 100 or less")
    private BigDecimal commissionPercentage; // Optional: Commission % (0-100) - must be provided with commissionPhoneNumber if used

    @Pattern(regexp = "^[0-9]{10,15}$", message = "Commission phone number must be between 10 and 15 digits")
    private String commissionPhoneNumber; // Optional: Phone number that will receive commission - must be provided with commissionPercentage if used

    private String notes; // Optional: Notes about the balance assignment
}

