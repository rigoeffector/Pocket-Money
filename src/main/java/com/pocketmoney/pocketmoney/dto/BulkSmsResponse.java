package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkSmsResponse {
    private int totalRecipients;
    private int successCount;
    private int failureCount;
    private int skippedCount;
    private List<RecipientResult> results;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecipientResult {
        private String phoneNumber;
        private boolean success;
        private String errorMessage;
    }
}
