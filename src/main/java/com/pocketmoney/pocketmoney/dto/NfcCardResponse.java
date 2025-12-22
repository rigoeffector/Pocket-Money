package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NfcCardResponse {
    private UUID userId;
    private String fullNames;
    private String phoneNumber;
    private Boolean isAssignedNfcCard;
    private String nfcCardId;
}

