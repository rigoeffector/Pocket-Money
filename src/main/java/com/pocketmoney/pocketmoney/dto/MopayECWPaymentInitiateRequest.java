package com.pocketmoney.pocketmoney.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

@Data
public class MopayECWPaymentInitiateRequest {
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
    
    @JsonProperty("callback_url")
    private String callback_url; // Optional callback URL for webhook notifications
    
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
