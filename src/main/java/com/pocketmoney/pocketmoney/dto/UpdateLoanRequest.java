package com.pocketmoney.pocketmoney.dto;

import com.pocketmoney.pocketmoney.entity.LoanStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class UpdateLoanRequest {
    @NotNull(message = "Loan ID is required")
    private UUID loanId;

    @NotNull(message = "Paid amount is required")
    @DecimalMin(value = "0.00", message = "Paid amount must be greater than or equal to 0")
    private BigDecimal paidAmount;

    @NotNull(message = "Loan status is required")
    private LoanStatus status;

    private String notes; // Optional notes about the update
}

