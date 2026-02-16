package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BulkWhatsAppRequest {
    @NotBlank(message = "Message is required")
    private String message;
    
    @NotEmpty(message = "At least one phone number is required")
    private List<String> phoneNumbers;
}
