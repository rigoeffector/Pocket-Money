package com.pocketmoney.pocketmoney.dto;

import lombok.Data;

@Data
public class EfasheValidateRequest {
    private String verticalId;
    private String customerAccountNumber;
}

