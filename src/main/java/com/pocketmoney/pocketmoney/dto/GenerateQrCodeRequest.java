package com.pocketmoney.pocketmoney.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class GenerateQrCodeRequest {
    // Optional: If not provided, will use the authenticated merchant's receiver ID
    private UUID receiverId;
    
    // Note: paymentCategoryId is not needed - QR Code category is always used
}
