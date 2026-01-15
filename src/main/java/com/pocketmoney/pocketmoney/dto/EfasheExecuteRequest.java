package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class EfasheExecuteRequest {
    @JsonProperty("trxId")
    private String trxId;
    
    @JsonProperty("customerAccountNumber")
    private String customerAccountNumber;
    
    @JsonProperty("amount")
    private BigDecimal amount;
    
    @JsonProperty("verticalId")
    private String verticalId;
    
    @JsonProperty("deliveryMethodId")
    private String deliveryMethodId; // print, email, sms, direct_topup
    
    @JsonProperty("deliverTo")
    private String deliverTo; // Optional - required if deliveryMethodId is not print or direct_topup
    
    @JsonProperty("callBack")
    private String callBack; // Optional - callback URL for async processing
}

