package com.pocketmoney.pocketmoney.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true) // Ignore unknown fields like "nid", "nidTp" to prevent parsing errors
public class MopayECWAccountHolderResponse {
    private Integer status;
    
    @JsonProperty("nid")
    private String nid; // National ID number
    
    @JsonProperty("nidTp")
    private String nidTp; // National ID type (e.g., "NRIN")
    
    @JsonProperty("firstname")
    private String firstname;
    
    @JsonProperty("lastname")
    private String lastname;
    
    // Some APIs return "name" directly instead of firstname/lastname
    @JsonProperty("name")
    private String name;
    
    // Some APIs return "fullName" directly
    @JsonProperty("fullName")
    private String fullName;
    
    private String gender;
    
    @JsonProperty("dob")
    private String dob; // Date of birth
    
    @JsonProperty("cob")
    private String cob; // Country of birth
    
    // Helper method to get full name - tries multiple sources
    public String getFullName() {
        // First try direct "name" or "fullName" fields
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        if (fullName != null && !fullName.trim().isEmpty()) {
            return fullName.trim();
        }
        // Then try combining firstname and lastname
        if (firstname != null && lastname != null) {
            return (firstname + " " + lastname).trim();
        } else if (firstname != null) {
            return firstname.trim();
        } else if (lastname != null) {
            return lastname.trim();
        }
        return null;
    }
}
