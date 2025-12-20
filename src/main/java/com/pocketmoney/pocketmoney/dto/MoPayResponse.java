package com.pocketmoney.pocketmoney.dto;

import lombok.Data;

@Data
public class MoPayResponse {
    private boolean success;
    private String message;
    private String transaction_id;
    private String status;
    private Object data;
}

