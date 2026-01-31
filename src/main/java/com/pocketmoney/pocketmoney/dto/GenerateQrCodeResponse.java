package com.pocketmoney.pocketmoney.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class GenerateQrCodeResponse {
    private UUID receiverId;
    private String receiverName;
    private UUID paymentCategoryId;
    private String paymentCategoryName;
    private String qrCodeData; // Base64 encoded QR code image
    private String qrCodeUrl; // URL/Deep link that the QR code encodes
}
