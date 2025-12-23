package com.pocketmoney.pocketmoney.dto;

import lombok.Data;

@Data
public class ApproveBalanceAssignmentRequest {
    private boolean approve; // true to approve, false to reject
}

