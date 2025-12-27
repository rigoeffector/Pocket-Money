package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateSubmerchantRequest {
    @NotNull(message = "Submerchant receiver ID is required")
    private UUID submerchantReceiverId; // The receiver to be linked as a submerchant
}

