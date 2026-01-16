package com.pocketmoney.pocketmoney.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class EfasheStatusResponse {
    private MoPayResponse moPayResponse;
    private EfasheValidateResponse validateResponse;
    private EfasheExecuteResponse executeResponse;
    private String transactionId;
    private String mopayStatus;
    private String efasheStatus;
    private String message;
    private String pollEndpoint; // EFASHE poll endpoint for async status checking
    private Integer retryAfterSecs; // Retry interval for polling
    private List<TransferInfo> transfers; // List of transfers made for this transaction
    
    @Data
    public static class TransferInfo {
        private String type; // "FULL_AMOUNT", "CUSTOMER_CASHBACK", "BESOFT_SHARE"
        private String fromPhone;
        private String toPhone;
        private BigDecimal amount;
        private String message;
        private String transactionId; // MoPay transaction ID for this transfer
    }
}

