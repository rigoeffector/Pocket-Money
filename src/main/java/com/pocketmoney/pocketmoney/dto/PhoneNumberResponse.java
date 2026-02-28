package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PhoneNumberResponse {
    private String phoneNumber;
    private String source; // "TRANSACTION", "EFASHE_CUSTOMER_PHONE", "EFASHE_ACCOUNT_NUMBER", "EFASHE_DELIVER_TO", "EFASHE_FULL_AMOUNT", "EFASHE_CASHBACK"
}
