package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiverLoginResponse {
    private String token;
    private String tokenType = "Bearer";
    private String userType = "MERCHANT";
    private UUID id;
    private String companyName;
    private String managerName;
    private String username;
    private String receiverPhone;
    private String momoAccountPhone;
    private String momoCode; // MoMo merchant code
    private String accountNumber;
    private ReceiverStatus status;
    private String email;
    private String address;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String country;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String countryCode;
    private String description;
    private BigDecimal walletBalance;
    private BigDecimal totalReceived;
    private Boolean isMainMerchant; // true if main merchant (no parent), false if submerchant
    private Boolean isFlexible; // true if receiver is in flexible mode (users can pay without checking receiver balance)
    private LocalDateTime lastTransactionDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

