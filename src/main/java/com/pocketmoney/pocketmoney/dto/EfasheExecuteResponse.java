package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class EfasheExecuteResponse {
    @JsonProperty("trxId")
    private String trxId;
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("message")
    private String message;
    
    @JsonProperty("pollEndpoint")
    private String pollEndpoint;
    
    @JsonProperty("retryAfterSecs")
    private Integer retryAfterSecs;
    
    // Additional fields that may be returned
    @JsonProperty("transactionId")
    private String transactionId;
    
    @JsonProperty("customerAccountNumber")
    private String customerAccountNumber;
    
    @JsonProperty("amount")
    private Double amount;
    
    @JsonProperty("token")
    private String token; // Token information for electricity purchases (delivered via SMS)
    
    @JsonProperty("extraInfo")
    private Object extraInfo; // Additional info that might contain token
    
    // HTTP status code from the execute API call (200 = success)
    private Integer httpStatusCode;
}

