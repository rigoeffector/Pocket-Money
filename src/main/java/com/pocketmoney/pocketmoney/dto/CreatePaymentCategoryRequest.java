package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreatePaymentCategoryRequest {
    @NotBlank(message = "Service name is required")
    private String name;

    private String description;
}

