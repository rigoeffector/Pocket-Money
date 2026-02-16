package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
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
public class ReceiverResponse {
    private UUID id;
    private String companyName;
    private String managerName;
    private String username;
    private String receiverPhone;
    private String momoAccountPhone; // MoMo account phone for receiving top-up payments (if configured)
    private String momoCode; // MoMo merchant code for QR code display
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
    private BigDecimal assignedBalance;
    private BigDecimal remainingBalance;
    private BigDecimal discountPercentage;
    private BigDecimal userBonusPercentage;
    private Boolean isFlexible; // true if receiver is in flexible mode (users can pay without checking receiver balance)
    private Integer pendingBalanceAssignments; // Count of pending balance assignment requests
    private List<CommissionInfo> commissionSettings; // Commission phone numbers and percentages
    
    // Submerchant relationship info
    private UUID parentReceiverId; // null if main merchant, set if submerchant
    private String parentReceiverCompanyName; // null if main merchant
    private Boolean isMainMerchant; // true if has no parent (main merchant), false if is submerchant
    private Integer submerchantCount; // Number of submerchants (only for main merchants)
    
    private LocalDateTime lastTransactionDate;
    private UUID lastTransactionId; // ID of the most recent transaction
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

