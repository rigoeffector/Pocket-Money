package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class EfasheInitiateResponse {
    private String transactionId;
    private EfasheServiceType serviceType;
    private BigDecimal amount;
    private Long customerPhone;
    private MoPayResponse moPayResponse;
    private String fullAmountPhone;
    private String cashbackPhone;
    private BigDecimal customerCashbackAmount;
    private BigDecimal agentCommissionAmount;
    private BigDecimal besoftShareAmount;
    private BigDecimal fullAmountPhoneReceives;
}

