package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResendMessageResponse {
    private int totalRequested;
    private int successCount;
    private int failureCount;
    private List<ResendResult> results;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResendResult {
        private java.util.UUID failedMessageId;
        private String phoneNumber;
        private boolean success;
        private String errorMessage;
    }
}
