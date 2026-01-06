package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.LoanStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoanInfo {
    private UUID loanId;
    private BigDecimal loanAmount;
    private BigDecimal paidAmount;
    private BigDecimal remainingAmount;
    private LoanStatus status;
    private LocalDateTime paidAt;
    private LocalDateTime lastPaymentAt;
}

