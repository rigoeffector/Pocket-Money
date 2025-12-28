package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import com.pocketmoney.pocketmoney.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String tokenType = "Bearer";
    private UUID id;
    private String username;
    private String email;
    private Role role;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // Additional fields for Auth (ADMIN/USER) - currently minimal, can be extended if needed
    
    // Additional fields for RECEIVER role - all receiver information
    private String companyName;
    private String managerName;
    private String receiverPhone;
    private String accountNumber;
    private ReceiverStatus status;
    private String address;
    private String description;
    private BigDecimal walletBalance;
    private BigDecimal totalReceived;
    private BigDecimal assignedBalance;
    private BigDecimal remainingBalance;
    private BigDecimal discountPercentage;
    private BigDecimal userBonusPercentage;
    private LocalDateTime lastTransactionDate;
    
    // Submerchant relationship info (for RECEIVER role)
    private UUID parentReceiverId;
    private String parentReceiverCompanyName;
    private Boolean isMainMerchant;
    private Integer submerchantCount;
    
    // For RECEIVER role only - list of available merchants/submerchants to switch between
    private List<MerchantInfo> availableMerchants;
    
    // For ADMIN role - when viewing as a merchant, this is the receiver ID being viewed
    private UUID viewAsReceiverId;
    
    // Indicates if the user is currently switching/viewing as another merchant/receiver
    private Boolean isSwitchingClaiming;
    
    // Indicates if the switch action was done by the main merchant
    private Boolean isDoneByMain;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MerchantInfo {
        private UUID id;
        private String username;
        private String companyName;
        private String managerName;
        private String receiverPhone;
        private String email;
        private Boolean isMainMerchant;
        private UUID parentReceiverId;
        private String parentReceiverCompanyName;
    }
}

