package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ElectricityTokenResponse {
    
    @JsonProperty("data")
    private List<TokenData> data;
    
    @Data
    public static class TokenData {
        @JsonProperty("units")
        private BigDecimal units;
        
        @JsonProperty("token")
        private String token;
        
        @JsonProperty("token2")
        private String token2;
        
        @JsonProperty("token3")
        private String token3;
        
        @JsonProperty("meterno")
        private String meterno;
        
        @JsonProperty("receipt_no")
        private String receiptNo;
        
        @JsonProperty("tstamp")
        private String tstamp; // ISO 8601 timestamp as string
        
        @JsonProperty("regulatory_fees")
        private BigDecimal regulatoryFees;
        
        @JsonProperty("amount")
        private BigDecimal amount;
        
        @JsonProperty("vat")
        private BigDecimal vat;
        
        @JsonProperty("customer_name")
        private String customerName;
    }
}

