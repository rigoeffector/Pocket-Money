package com.pocketmoney.pocketmoney.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

@Data
public class MoPayInitiateRequest {
    @JsonProperty("transaction_id")
    private String transaction_id;
    
    private BigDecimal amount;
    private String currency = "RWF";
    private Long phone; // DEBIT - must be number, not string
    private String payment_mode = "MOBILE";
    private String message;
    private String callback_url;
    private List<Transfer> transfers;

    @Data
    public static class Transfer {
        @JsonProperty("transaction_id")
        private String transaction_id;
        
        private BigDecimal amount;
        private Long phone; // RECEIVER - must be number, not string
        private String message;
    }
}

