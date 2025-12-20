package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.MoPayInitiateRequest;
import com.pocketmoney.pocketmoney.dto.MoPayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class MoPayService {

    private static final Logger logger = LoggerFactory.getLogger(MoPayService.class);

    @Value("${mopay.api.url:https://api.mopay.rw}")
    private String mopayApiUrl;

    @Value("${mopay.api.token:2fuytPgoD4At0FE1MgoF08xuAr03xSvkJ1ZlGrT5jYFyolQsBU7XKU28OW4Oqq3a}")
    private String mopayApiToken;

    private final RestTemplate restTemplate;

    public MoPayService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public MoPayResponse initiatePayment(MoPayInitiateRequest request) {
        String url = mopayApiUrl + "/initiate-payment";
        
        logger.info("Initiating MoPay payment to: {}", url);
        logger.debug("MoPay request: {}", request);
        
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
            
            MoPayResponse responseBody = response.getBody();
            logger.info("MoPay response status: {}", response.getStatusCode());
            logger.debug("MoPay response body: {}", responseBody);
            
            if (responseBody == null) {
                logger.warn("MoPay returned null response body");
                MoPayResponse errorResponse = new MoPayResponse();
                errorResponse.setSuccess(false);
                errorResponse.setMessage("MoPay returned null response");
                return errorResponse;
            }
            
            return responseBody;
        } catch (Exception e) {
            logger.error("Error initiating MoPay payment: ", e);
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

