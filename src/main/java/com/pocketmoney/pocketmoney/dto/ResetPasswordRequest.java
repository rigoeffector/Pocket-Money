package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ResetPasswordRequest {
    @NotBlank(message = "New password is required")
    @Pattern(regexp = "^.{6,}$", message = "Password must be at least 6 characters")
    private String newPassword;
}

