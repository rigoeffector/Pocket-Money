package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class EfasheInitiateForOtherRequest {
    // transaction_id is generated automatically - no need to provide
    
    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    private BigDecimal amount;
    
    private String currency = "RWF";
    
    @NotNull(message = "Phone number is required")
    @NotBlank(message = "Phone number cannot be blank")
    private String phone; // DEBIT - customer phone for MoPay payment (the person paying)
    
    @NotNull(message = "Another phone number is required")
    @NotBlank(message = "Another phone number cannot be blank")
    private String anotherPhoneNumber; // Phone number for EFASHE validate (the person receiving airtime)
    
    private String payment_mode = "MOBILE";
    
    private String message;
    
    private String callback_url;
    
    @NotNull(message = "Service type is required")
    private EfasheServiceType serviceType; // Should be AIRTIME only
}

