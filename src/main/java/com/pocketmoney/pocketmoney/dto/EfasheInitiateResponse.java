package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class EfasheInitiateResponse {
    private String transactionId;
    private EfasheServiceType serviceType;
    private BigDecimal amount;
    private String customerPhone;
    private String customerAccountName; // Customer account name from validate (e.g., "MUHINZI ANDRE" for electricity, TIN owner for RRA)
    private MoPayResponse moPayResponse;
    private EfasheValidateResponse efasheValidateResponse; // Complete EFASHE validate response
    private String fullAmountPhone;
    private String cashbackPhone;
    private BigDecimal customerCashbackAmount;
    private BigDecimal agentCommissionAmount;
    private BigDecimal besoftShareAmount;
    private BigDecimal fullAmountPhoneReceives;
    private String validated; // Validation status: INITIAL (validated but not processed), PROCESS (ready to process MoPay)
}

