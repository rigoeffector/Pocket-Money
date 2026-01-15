package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class EfasheValidateResponse {
    @JsonProperty("trxId")
    private String trxId;
    
    @JsonProperty("pdtId")
    private String pdtId;
    
    @JsonProperty("pdtName")
    private String pdtName;
    
    @JsonProperty("pdtStatusId")
    private String pdtStatusId;
    
    @JsonProperty("verticalId")
    private String verticalId;
    
    @JsonProperty("customerAccountNumber")
    private String customerAccountNumber;
    
    @JsonProperty("customerAccountName")
    private String customerAccountName;
    
    @JsonProperty("svcProviderName")
    private String svcProviderName;
    
    @JsonProperty("vendUnitId")
    private String vendUnitId;
    
    @JsonProperty("vendMin")
    private Double vendMin;
    
    @JsonProperty("vendMax")
    private Double vendMax;
    
    @JsonProperty("selectAmount")
    private List<SelectAmount> selectAmount;
    
    @JsonProperty("localStockMgt")
    private Boolean localStockMgt;
    
    @JsonProperty("stockedPdts")
    private Integer stockedPdts;
    
    @JsonProperty("stock")
    private List<Stock> stock;
    
    @JsonProperty("trxResult")
    private String trxResult;
    
    @JsonProperty("availTrxBalance")
    private Double availTrxBalance;
    
    @JsonProperty("deliveryMethods")
    private List<DeliveryMethod> deliveryMethods;
    
    @JsonProperty("extraInfo")
    private Object extraInfo;
    
    @Data
    public static class DeliveryMethod {
        private String id;
        private String name;
    }
    
    @Data
    public static class SelectAmount {
        private Double amount;
        private String currency;
    }
    
    @Data
    public static class Stock {
        @JsonProperty("skuId")
        private String skuId;
        
        @JsonProperty("skuName")
        private String skuName;
        
        @JsonProperty("worth")
        private Double worth;
        
        @JsonProperty("vendMax")
        private Double vendMax;
        
        @JsonProperty("vendMin")
        private Double vendMin;
        
        @JsonProperty("statusId")
        private String statusId;
        
        @JsonProperty("promo")
        private Boolean promo;
        
        @JsonProperty("promoExpires")
        private String promoExpires;
    }
}

