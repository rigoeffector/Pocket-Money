package com.pocketmoney.pocketmoney.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketmoney.pocketmoney.dto.MopayOpenApiInitiateRequest;
import com.pocketmoney.pocketmoney.dto.MoPayResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service
public class MopayOpenApiService {

    private static final Logger logger = LoggerFactory.getLogger(MopayOpenApiService.class);

    @Value("${mopay_open_api.api.url:https://api.mopay.rw}")
    private String mopayApiUrl;

    @Value("${mopay_open_api.api.token:2fuytPgoD4At0FE1MgoF08xuAr03xSvkJ1ZlGrT5jYFyolQsBU7XKU28OW4Oqq3a}")
    private String mopayApiToken;

    private final RestTemplate restTemplate;

    public MopayOpenApiService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public MoPayResponse initiatePayment(MopayOpenApiInitiateRequest request) {
        String url = mopayApiUrl + "/initiate-payment";
        
        logger.info("Initiating MopayOpenApi payment to: {}", url);
        logger.info("MopayOpenApi request - Phone: {}, Amount: {}, Currency: {}", request.getPhone(), request.getAmount(), request.getCurrency());
        if (request.getTransfers() != null && !request.getTransfers().isEmpty()) {
            for (int i = 0; i < request.getTransfers().size(); i++) {
                MopayOpenApiInitiateRequest.Transfer transfer = request.getTransfers().get(i);
                logger.info("Transfer #{} - Phone: {}, Amount: {}, Message: {}", i + 1, transfer.getPhone(), transfer.getAmount(), transfer.getMessage());
            }
        }
        logger.debug("Full MopayOpenApi request: {}", request);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(mopayApiToken);

        HttpEntity<MopayOpenApiInitiateRequest> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<MoPayResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    MoPayResponse.class
            );
            
            MoPayResponse responseBody = response.getBody();
            int httpStatusCode = response.getStatusCode().value();
            logger.info("MopayOpenApi HTTP response status: {}", httpStatusCode);
            logger.debug("MopayOpenApi response body: {}", responseBody);
            
            if (responseBody == null) {
                logger.warn("MopayOpenApi returned null response body");
                MoPayResponse errorResponse = new MoPayResponse();
                errorResponse.setStatus(httpStatusCode);
                errorResponse.setSuccess(false);
                errorResponse.setMessage("MopayOpenApi returned null response");
                return errorResponse;
            }
            
            // Set HTTP status code in response if not already set from JSON body
            if (responseBody.getStatus() == null) {
                responseBody.setStatus(httpStatusCode);
            }
            
            return responseBody;
        } catch (HttpClientErrorException e) {
            // Handle HTTP client errors (4xx) - extract response body properly
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            
            logger.error("MopayOpenApi HTTP error - Status: {}, Response: {}", statusCode, responseBody);
            
            MoPayResponse errorResponse = new MoPayResponse();
            errorResponse.setStatus(statusCode);
            errorResponse.setSuccess(false);
            
            // Try to parse the error response body
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                MoPayResponse parsedError = objectMapper.readValue(responseBody, MoPayResponse.class);
                // Use errorMessage if available, otherwise use message
                String errorMsg = parsedError.getErrorMessage() != null ? parsedError.getErrorMessage() : parsedError.getMessage();
                errorResponse.setMessage(errorMsg);
                errorResponse.setTransactionId(parsedError.getTransactionId());
                logger.info("Parsed MopayOpenApi error response - Status: {}, Message: {}, TransactionId: {}", 
                    statusCode, errorMsg, parsedError.getTransactionId());
            } catch (Exception parseException) {
                logger.warn("Failed to parse MopayOpenApi error response, using raw body: {}", responseBody);
                // If parsing fails, use the raw response body as message
                errorResponse.setMessage(responseBody != null && !responseBody.isEmpty() ? responseBody : e.getMessage());
            }
            
            return errorResponse;
        } catch (Exception e) {
            logger.error("Error initiating MopayOpenApi payment: ", e);
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

