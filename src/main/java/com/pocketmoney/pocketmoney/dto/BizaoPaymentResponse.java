package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class BizaoPaymentResponse {
    private Integer status; // HTTP status code (e.g., 200, 201)
    
    @JsonProperty("transactionId")
    private String transactionId;
    
    // For error responses
    private String message;
    private Boolean success;
    
    @JsonProperty("errorMessage")
    private String errorMessage;
    
    // Fields from BizaoPayment API response
    private java.math.BigDecimal amount;
    private Integer charges;
    private String currency;
    
    @JsonProperty("momoRef")
    private String momoRef;
    
    @JsonProperty("statusDesc")
    private String statusDesc; // PENDING, SUCCESSFUL, FAILED, etc.
    
    @JsonProperty("paymentType")
    private String paymentType; // From status check endpoint
    
    private String time; // From status check endpoint (ISO 8601 format)
    
    @JsonProperty("transactionType")
    private String transactionType; // DEBIT, CREDIT, etc. (from status check endpoint)
    
    // Additional fields that might be in the response
    @JsonProperty("account_no")
    private String account_no;
    
    private String title;
    private String details;
    
    @JsonProperty("payment_type")
    private String payment_type;
}
