package com.pocketmoney.pocketmoney.dto;

import lombok.Data;
import java.util.List;

@Data
public class SmsRequest {
    private String message;
    private List<String> phoneNumbers;
}

