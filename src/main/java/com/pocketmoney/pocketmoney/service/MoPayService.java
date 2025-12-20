package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.MoPayInitiateRequest;
import com.pocketmoney.pocketmoney.dto.MoPayResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class MoPayService {

    @Value("${mopay.api.url:https://api.mopay.rw}")
    private String mopayApiUrl;

    @Value("${mopay.api.token:2fuytPgoD4At0FE1MgoF08xuAr03xSvkJ1ZlGrT5jYFyolQsBU7XKU28OW4Oqq3a}")
    private String mopayApiToken;

    private final RestTemplate restTemplate;

    public MoPayService() {
        this.restTemplate = new RestTemplate();
    }

    public MoPayResponse initiatePayment(MoPayInitiateRequest request) {
        String url = mopayApiUrl + "/initiate-payment";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(mopayApiToken);

        HttpEntity<MoPayInitiateRequest> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<MoPayResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    MoPayResponse.class
            );
            return response.getBody();
        } catch (Exception e) {
            MoPayResponse errorResponse = new MoPayResponse();
            errorResponse.setSuccess(false);
            errorResponse.setMessage("Failed to initiate payment: " + e.getMessage());
            return errorResponse;
        }
    }

    public MoPayResponse checkTransactionStatus(String transactionId) {
        String url = mopayApiUrl + "/check-status/" + transactionId;
        
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(mopayApiToken);

        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<MoPayResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    MoPayResponse.class
            );
            return response.getBody();
        } catch (Exception e) {
            MoPayResponse errorResponse = new MoPayResponse();
            errorResponse.setSuccess(false);
            errorResponse.setMessage("Failed to check transaction status: " + e.getMessage());
            return errorResponse;
        }
    }

    public MoPayResponse getCustomerInfo(String phone) {
        String url = mopayApiUrl + "/customer-info?phone=" + phone;
        
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(mopayApiToken);

        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<MoPayResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    MoPayResponse.class
            );
            return response.getBody();
        } catch (Exception e) {
            MoPayResponse errorResponse = new MoPayResponse();
            errorResponse.setSuccess(false);
            errorResponse.setMessage("Failed to get customer info: " + e.getMessage());
            return errorResponse;
        }
    }
}

