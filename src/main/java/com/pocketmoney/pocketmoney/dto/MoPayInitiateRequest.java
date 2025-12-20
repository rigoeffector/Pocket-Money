package com.pocketmoney.pocketmoney.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MoPayInitiateRequest {
    private String transaction_id;
    private BigDecimal amount;
    private String currency = "RWF";
    private String phone; // DEBIT
    private String payment_mode = "MOBILE";
    private String message;
    private String callback_url;
    private List<Transfer> transfers;

    @Data
    public static class Transfer {
        private String transaction_id;
        private BigDecimal amount;
        private String phone; // RECEIVER
        private String message;
    }
}

