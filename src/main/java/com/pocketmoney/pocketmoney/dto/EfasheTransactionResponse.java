package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EfasheTransactionResponse {
    private UUID id;
    private String transactionId;
    private EfasheServiceType serviceType;
    private String customerPhone;
    private String customerAccountNumber;
    private String customerAccountName; // Customer account name (e.g., "MUHINZI ANDRE" for electricity, TIN owner for RRA)
    private BigDecimal amount;
    private String currency;
    private String trxId;
    private String mopayTransactionId;
    private String mopayStatus;
    private String efasheStatus;
    private String deliveryMethodId;
    private String deliverTo;
    private String pollEndpoint;
    private Integer retryAfterSecs;
    private String message;
    private String errorMessage;
    private BigDecimal customerCashbackAmount;
    private BigDecimal besoftShareAmount;
    private String fullAmountPhone;
    private String cashbackPhone;
    private Boolean cashbackSent;
    
    // Service-specific information
    private String token; // Token number for ELECTRICITY
    private String kwh; // KWH units for ELECTRICITY
    private String decoderNumber; // Decoder number for TV (same as customerAccountNumber)
    
    // PAY_CUSTOMER: list of customers paid (names, phones, amounts)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private List<TransferRecipientInfo> transferRecipients;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferRecipientInfo {
        private String phone;
        private String name;
        private BigDecimal amount;
    }
}

