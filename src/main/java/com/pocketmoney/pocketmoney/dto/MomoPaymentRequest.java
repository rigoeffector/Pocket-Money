package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class MomoPaymentRequest {
    // Optional user ID - if not provided, payerPhone is required
    private UUID userId;

    @NotNull(message = "Payment category ID is required")
    private UUID paymentCategoryId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    private BigDecimal amount;

    @NotNull(message = "Receiver ID is required")
    private UUID receiverId;

    // Receiver phone number - if not provided, will use default hardcoded number (250794230137)
    private String receiverPhone;

    private String message;

    // Payer phone number - required if userId is not provided
    private String payerPhone;
}

