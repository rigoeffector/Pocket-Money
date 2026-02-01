package com.pocketmoney.pocketmoney.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyReportProductSummary {
    private String product; // "GASOLINE" or "DIESEL"
    private Long transactions; // Count of transactions
    private BigDecimal volume; // Total volume in litres
    private BigDecimal amount; // Total amount
}
