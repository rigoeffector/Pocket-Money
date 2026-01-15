package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class EfasheInitiateRequest {
    // transaction_id is generated automatically - no need to provide
    
    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    private BigDecimal amount;
    
    private String currency = "RWF";
    
    @NotNull(message = "Phone number is required")
    private Long phone; // DEBIT - customer phone
    
    private String payment_mode = "MOBILE";
    
    private String message;
    
    private String callback_url;
    
    @NotNull(message = "Service type is required")
    private EfasheServiceType serviceType; // AIRTIME, MTN, RRA, TV
}

