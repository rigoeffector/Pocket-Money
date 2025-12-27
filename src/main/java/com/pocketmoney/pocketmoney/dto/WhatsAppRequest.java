package com.pocketmoney.pocketmoney.dto;

import lombok.Data;
import java.util.List;

@Data
public class WhatsAppRequest {
    private String message;
    private List<String> phoneNumbers;
}

