package com.pocketmoney.pocketmoney.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

@Data
public class BizaoPaymentInitiateRequest {
    @JsonProperty("transactionId")
    private String transactionId;
    
    @JsonProperty("account_no")
    private String account_no; // DEBIT - phone number
    
    private String title;
    private String details;
    
    @JsonProperty("payment_type")
    private String payment_type = "momo";
    
    private BigDecimal amount;
    private String currency = "RWF";
    private String message;
    
    private List<Transfer> transfers;

    @Data
    public static class Transfer {
        @JsonProperty("transactionId")
        private String transactionId;
        
        @JsonProperty("account_no")
        private String account_no; // RECEIVER - phone number
        
        @JsonProperty("payment_type")
        private String payment_type = "momo";
        
        private BigDecimal amount;
        private String currency = "RWF";
        private String message;
    }
}
