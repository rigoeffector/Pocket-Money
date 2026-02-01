package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

@Data
public class PayCustomerRequest {
    @JsonProperty("transaction_id")
    private String transaction_id; // Optional
    
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    private BigDecimal amount;
    
    private String currency = "RWF";
    
    @NotBlank(message = "Phone number is required")
    private String phone; // DEBIT - phone as string
    
    private String payment_mode = "MOBILE";
    
    private String message;
    
    private String callback_url;
    
    // Receiver ID - optional for both ADMIN and RECEIVER users
    // - ADMIN: If not provided, uses first active receiver
    // - RECEIVER: If not provided, uses authenticated receiver
    private java.util.UUID receiverId;
    
    @NotNull(message = "At least one transfer is required")
    private List<Transfer> transfers;

    @Data
    public static class Transfer {
        @JsonProperty("transaction_id")
        private String transaction_id; // Optional
        
        @NotNull(message = "Transfer amount is required")
        @DecimalMin(value = "0.01", message = "Transfer amount must be greater than 0")
        private BigDecimal amount;
        
        @NotNull(message = "Receiver phone number is required")
        private Long phone; // RECEIVER - phone as number
        
        private String message;
    }
}
