package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AssignNfcCardRequest {
    @NotBlank(message = "NFC Card ID is required")
    private String nfcCardId;

    @NotBlank(message = "PIN is required")
    @Pattern(regexp = "^[0-9]{4}$", message = "PIN must be exactly 4 digits")
    private String pin;
}

