package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class EfashePollStatusResponse {
    @JsonProperty("trxId")
    private String trxId;
    
    @JsonProperty("status")
    private String status; // PENDING, SUCCESS, FAILED
    
    @JsonProperty("message")
    private String message;
}

