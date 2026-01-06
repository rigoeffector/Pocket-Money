package com.pocketmoney.pocketmoney.entity;

public enum LoanStatus {
    PENDING,      // Loan has been given but not paid back yet
    PARTIALLY_PAID,  // Some amount has been paid back
    COMPLETED     // Fully paid back
}

