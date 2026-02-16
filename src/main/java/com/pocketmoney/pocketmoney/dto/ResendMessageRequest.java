package com.pocketmoney.pocketmoney.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class ResendMessageRequest {
    @NotEmpty(message = "At least one failed message ID is required")
    private List<UUID> failedMessageIds;
}
