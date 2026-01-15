package com.pocketmoney.pocketmoney.dto;

import lombok.Data;

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
}

