package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class EfasheAuthRequest {
    @JsonProperty("api_key")
    private String apiKey;
    
    @JsonProperty("api_secret")
    private String apiSecret;
}

