package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class MopayECWAccountHolderResponse {
    private Integer status;
    
    @JsonProperty("firstname")
    private String firstname;
    
    @JsonProperty("lastname")
    private String lastname;
    
    private String gender;
    
    @JsonProperty("dob")
    private String dob; // Date of birth
    
    @JsonProperty("cob")
    private String cob; // Country of birth
    
    // Helper method to get full name
    public String getFullName() {
        if (firstname != null && lastname != null) {
            return firstname + " " + lastname;
        } else if (firstname != null) {
            return firstname;
        } else if (lastname != null) {
            return lastname;
        }
        return null;
    }
}
