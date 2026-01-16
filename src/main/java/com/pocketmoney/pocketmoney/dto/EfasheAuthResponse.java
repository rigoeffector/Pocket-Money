package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class EfasheAuthResponse {
    @JsonProperty("data")
    private AuthData data;

    @Data
    public static class AuthData {
        @JsonProperty("accessToken")
        private String accessToken;
        
        @JsonProperty("refreshToken")
        private String refreshToken;
        
        @JsonProperty("accessTokenExpiresAt")
        private String accessTokenExpiresAt;
        
        @JsonProperty("refreshTokenExpiresAt")
        private String refreshTokenExpiresAt;
        
        @JsonProperty("staffAccount")
        private Object staffAccount;
        
        @JsonProperty("agencyPOP")
        private AgencyPOP agencyPOP;
        
        @JsonProperty("agencyAccount")
        private AgencyAccount agencyAccount;
    }

    @Data
    public static class AgencyPOP {
        @JsonProperty("popId")
        private String popId;
        
        @JsonProperty("popName")
        private String popName;
        
        @JsonProperty("presenceId")
        private String presenceId;
        
        @JsonProperty("classId")
        private String classId;
        
        @JsonProperty("popShortCode")
        private String popShortCode;
        
        @JsonProperty("popStatusId")
        private String popStatusId;
        
        @JsonProperty("address1Id")
        private String address1Id;
        
        @JsonProperty("address2Id")
        private String address2Id;
        
        @JsonProperty("streetAddress")
        private String streetAddress;
    }

    @Data
    public static class AgencyAccount {
        @JsonProperty("agencyId")
        private String agencyId;
        
        @JsonProperty("agencyName")
        private String agencyName;
        
        @JsonProperty("agencyShortCode")
        private String agencyShortCode;
        
        @JsonProperty("agencyLevelId")
        private String agencyLevelId;
        
        @JsonProperty("agencyStatusId")
        private String agencyStatusId;
    }
}

