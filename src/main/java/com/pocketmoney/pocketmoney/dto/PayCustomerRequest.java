package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

@Data
public class PayCustomerRequest {
    @JsonProperty("transaction_id")
    private String transaction_id; // Optional
    
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    private BigDecimal amount;
    
    private String currency = "RWF";
    
    private String phone; // DEBIT - phone as string (legacy field)
    
    @JsonProperty("account_no")
    private String account_no; // DEBIT - phone as string (preferred field)
    
    @JsonProperty("payment_type")
    private String payment_type; // Optional, defaults to "momo"
    
    private String title; // Optional, defaults to "payment"
    
    private String details; // Optional, defaults to "payment"
    
    @JsonProperty("transactionId")
    private String transactionId; // Alternative to transaction_id
    
    private String payment_mode = "MOBILE";
    
    // Helper method to get payer phone number from either field
    public String getPayerPhoneNumber() {
        if (account_no != null && !account_no.trim().isEmpty()) {
            return account_no.trim();
        } else if (phone != null && !phone.trim().isEmpty()) {
            return phone.trim();
        }
        return null;
    }
    
    // Helper method to get transaction ID from either field
    public String getTransactionId() {
        if (transactionId != null && !transactionId.trim().isEmpty()) {
            return transactionId;
        } else if (transaction_id != null && !transaction_id.trim().isEmpty()) {
            return transaction_id;
        }
        return null;
    }
    
    private String message;
    
    private String callback_url;
    
    // Receiver ID - optional for both ADMIN and RECEIVER users
    // - ADMIN: If not provided, uses first active receiver
    // - RECEIVER: If not provided, uses authenticated receiver
    private java.util.UUID receiverId;
    
    @NotNull(message = "At least one transfer is required")
    private List<Transfer> transfers;

    @Data
    public static class Transfer {
        @JsonProperty("transaction_id")
        private String transaction_id; // Optional
        
        @JsonProperty("transactionId")
        private String transactionId; // Alternative field name
        
        @NotNull(message = "Transfer amount is required")
        @DecimalMin(value = "0.01", message = "Transfer amount must be greater than 0")
        private BigDecimal amount;
        
        private Long phone; // RECEIVER - phone as number (legacy field)
        
        @JsonProperty("account_no")
        private String account_no; // RECEIVER - phone as string (preferred field)
        
        @JsonProperty("payment_type")
        private String payment_type; // Optional
        
        private String currency; // Optional
        
        private String message;
        
        // Helper method to get phone number from either field
        public String getPhoneNumber() {
            if (account_no != null && !account_no.trim().isEmpty()) {
                return account_no.trim();
            } else if (phone != null) {
                return String.valueOf(phone);
            }
            return null;
        }
        
        // Helper method to get transaction ID from either field
        public String getTransactionId() {
            if (transactionId != null && !transactionId.trim().isEmpty()) {
                return transactionId;
            } else if (transaction_id != null && !transaction_id.trim().isEmpty()) {
                return transaction_id;
            }
            return null;
        }
    }
}
