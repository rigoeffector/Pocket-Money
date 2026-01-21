package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class EfasheRefundRequest {
    @NotNull(message = "Admin phone number is required")
    @Pattern(regexp = "^[0-9]{10,15}$", message = "Admin phone number must be between 10 and 15 digits")
    private String adminPhone; // Required: Admin phone (DEBIT) - who will pay for the refund
    
    @NotNull(message = "Receiver phone number is required")
    @Pattern(regexp = "^[0-9]{10,15}$", message = "Receiver phone number must be between 10 and 15 digits")
    private String receiverPhone; // Customer phone to receive the refund
    
    private String message; // Optional: Custom message for the refund
}
