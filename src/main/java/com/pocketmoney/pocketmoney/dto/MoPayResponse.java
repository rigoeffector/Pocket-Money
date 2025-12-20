package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class MoPayResponse {
    private Integer status; // HTTP status code (e.g., 201)
    private String transactionId; // MoPay transaction ID
    
    // For error responses
    private String message;
    private Boolean success;
    
    // Legacy fields for backward compatibility (from check-status endpoint)
    @JsonProperty("transaction_id")
    private String transaction_id;
}

