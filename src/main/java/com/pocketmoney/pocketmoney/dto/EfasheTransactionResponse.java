package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

