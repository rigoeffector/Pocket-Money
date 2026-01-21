package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ElectricityTokensResponse {
    @JsonProperty("data")
    private List<ElectricityTokenData> data;
    
    @JsonProperty("status")
    private Integer status;
    
    @Data
    public static class ElectricityTokenData {
        @JsonProperty("units")
        private Double units; // Number of Units of the token
        
        @JsonProperty("token")
        private String token; // First token
        
        @JsonProperty("token2")
        private String token2; // Second token (optional)
        
        @JsonProperty("token3")
        private String token3; // Third token (optional)
        
        @JsonProperty("meterno")
        private String meterno; // Meter number
        
        @JsonProperty("receipt_no")
        private String receiptNo; // Receipt number
        
        @JsonProperty("tstamp")
        private String tstamp; // Timestamp (as string to avoid Jackson parsing issues)
        
        @JsonProperty("regulatory_fees")
        private Double regulatoryFees; // Regulatory fees
        
        @JsonProperty("amount")
        private Double amount; // Amount
        
        @JsonProperty("vat")
        private Double vat; // VAT
        
        @JsonProperty("customer_name")
        private String customerName; // Customer name
    }
}
