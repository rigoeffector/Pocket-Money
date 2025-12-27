package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    
    // For RECEIVER role only - list of available merchants/submerchants to switch between
    private List<MerchantInfo> availableMerchants;
    
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

