package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayCustomerValidateResponse {
    private String payerPhone; // Normalized payer phone number
    private BigDecimal totalAmount; // Total amount to be paid
    private List<RecipientInfo> recipients; // List of recipients with their names
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecipientInfo {
        private String phone; // Recipient phone number
        private String name; // Recipient name from account holder information (null if not available)
        private BigDecimal amount; // Transfer amount
        private boolean nameAvailable; // Whether name was successfully retrieved
        private String errorMessage; // Error message if name retrieval failed
    }
}
