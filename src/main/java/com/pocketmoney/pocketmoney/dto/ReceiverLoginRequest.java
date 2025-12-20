package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReceiverLoginRequest {
    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;
}

