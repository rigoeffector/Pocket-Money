package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdatePaymentCategoryRequest {
    @NotBlank(message = "Payment category name is required")
    private String name;

    private String description;
    
    private Boolean isActive;
}

