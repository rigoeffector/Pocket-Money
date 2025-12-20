package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ResetPinRequest {
    @NotBlank(message = "New PIN is required")
    @Pattern(regexp = "^[0-9]{4}$", message = "PIN must be exactly 4 digits")
    private String newPin;
}

