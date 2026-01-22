package com.pocketmoney.pocketmoney.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketmoney.pocketmoney.dto.BizaoPaymentInitiateRequest;
import com.pocketmoney.pocketmoney.dto.BizaoPaymentResponse;
import com.pocketmoney.pocketmoney.dto.BizaoAccountHolderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service
public class BizaoPaymentService {

    private static final Logger logger = LoggerFactory.getLogger(BizaoPaymentService.class);

    @Value("${bizaopayment.api.url:http://41.186.14.66:443}")
    private String bizaoPaymentApiUrl;

    @Value("${bizaopayment.api.token:dW5vdGlmeTpRMG5XMUMhLkBEM1YjJTgqTTIxMkBf}")
    private String bizaoPaymentApiToken;

    private final RestTemplate restTemplate;

    public BizaoPaymentService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public BizaoPaymentResponse initiatePayment(BizaoPaymentInitiateRequest request) {
        String url = bizaoPaymentApiUrl + "/api/v1/payment";
        
        logger.info("Initiating BizaoPayment payment to: {}", url);
        logger.info("BizaoPayment request - Account No: {}, Amount: {}, Currency: {}, Title: {}", 
            request.getAccount_no(), request.getAmount(), request.getCurrency(), request.getTitle());
        
        if (request.getTransfers() != null && !request.getTransfers().isEmpty()) {
            for (int i = 0; i < request.getTransfers().size(); i++) {
                BizaoPaymentInitiateRequest.Transfer transfer = request.getTransfers().get(i);
                logger.info("Transfer #{} - Account No: {}, Amount: {}, Message: {}", 
                    i + 1, transfer.getAccount_no(), transfer.getAmount(), transfer.getMessage());
            }
        }
        logger.debug("Full BizaoPayment request: {}", request);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // BizaoPayment expects Authorization header with the token directly
        headers.set("Authorization", bizaoPaymentApiToken);
        logger.debug("BizaoPayment request headers - Authorization: {}...", bizaoPaymentApiToken.substring(0, Math.min(10, bizaoPaymentApiToken.length())));

        HttpEntity<BizaoPaymentInitiateRequest> entity = new HttpEntity<>(request, headers);

        try {
            // First, get raw response to check structure
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            
            int httpStatusCode = rawResponse.getStatusCode().value();
            String responseBodyString = rawResponse.getBody();
            
            logger.info("BizaoPayment HTTP response status: {}", httpStatusCode);
            logger.info("BizaoPayment raw response body: {}", responseBodyString);
            
            if (responseBodyString == null || responseBodyString.trim().isEmpty()) {
                logger.warn("BizaoPayment returned null or empty response body");
                BizaoPaymentResponse errorResponse = new BizaoPaymentResponse();
                errorResponse.setStatus(httpStatusCode);
                errorResponse.setSuccess(false);
                errorResponse.setMessage("BizaoPayment returned null or empty response");
                return errorResponse;
            }
            
            // Try to parse the response
            ObjectMapper objectMapper = new ObjectMapper();
            BizaoPaymentResponse responseBody;
            
            try {
                // First, try to parse as direct BizaoPaymentResponse
                responseBody = objectMapper.readValue(responseBodyString, BizaoPaymentResponse.class);
                
                // Check if response is wrapped in a "data" field
                if (responseBody.getTransactionId() == null) {
                    try {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> responseMap = objectMapper.readValue(responseBodyString, java.util.Map.class);
                        if (responseMap.containsKey("data")) {
                            Object dataObj = responseMap.get("data");
                            if (dataObj instanceof java.util.Map) {
                                @SuppressWarnings("unchecked")
                                java.util.Map<String, Object> dataMap = (java.util.Map<String, Object>) dataObj;
                                // Try to extract transactionId from data
                                if (dataMap.containsKey("transactionId")) {
                                    responseBody.setTransactionId(dataMap.get("transactionId").toString());
                                    logger.info("✅ Extracted transactionId from 'data' field: {}", responseBody.getTransactionId());
                                }
                            }
                        }
                        // Also check for transactionId at root level with different casing
                        if (responseBody.getTransactionId() == null) {
                            for (String key : responseMap.keySet()) {
                                if (key != null && (key.equalsIgnoreCase("transactionId") || key.equalsIgnoreCase("transaction_id") || key.equalsIgnoreCase("trxId"))) {
                                    responseBody.setTransactionId(responseMap.get(key).toString());
                                    logger.info("✅ Extracted transactionId from field '{}': {}", key, responseBody.getTransactionId());
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Could not parse response as Map to check for wrapped structure: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to parse BizaoPayment response: {}", e.getMessage());
                // Try to manually extract fields from the JSON string
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> responseMap = objectMapper.readValue(responseBodyString, java.util.Map.class);
                    responseBody = new BizaoPaymentResponse();
                    responseBody.setStatus(httpStatusCode);
                    
                    // Extract all available fields
                    if (responseMap.containsKey("transactionId")) {
                        responseBody.setTransactionId(responseMap.get("transactionId").toString());
                    }
                    if (responseMap.containsKey("amount")) {
                        Object amountObj = responseMap.get("amount");
                        if (amountObj instanceof Number) {
                            responseBody.setAmount(new java.math.BigDecimal(amountObj.toString()));
                        }
                    }
                    if (responseMap.containsKey("charges")) {
                        Object chargesObj = responseMap.get("charges");
                        if (chargesObj instanceof Number) {
                            responseBody.setCharges(((Number) chargesObj).intValue());
                        }
                    }
                    if (responseMap.containsKey("currency")) {
                        responseBody.setCurrency(responseMap.get("currency").toString());
                    }
                    if (responseMap.containsKey("momoRef")) {
                        responseBody.setMomoRef(responseMap.get("momoRef").toString());
                    }
                    if (responseMap.containsKey("status")) {
                        Object statusObj = responseMap.get("status");
                        if (statusObj instanceof Number) {
                            responseBody.setStatus(((Number) statusObj).intValue());
                        }
                    }
                    if (responseMap.containsKey("statusDesc")) {
                        responseBody.setStatusDesc(responseMap.get("statusDesc").toString());
                    }
                    if (responseMap.containsKey("paymentType")) {
                        responseBody.setPaymentType(responseMap.get("paymentType").toString());
                    }
                    if (responseMap.containsKey("time")) {
                        responseBody.setTime(responseMap.get("time").toString());
                    }
                    if (responseMap.containsKey("transactionType")) {
                        responseBody.setTransactionType(responseMap.get("transactionType").toString());
                    }
                    
                    // Set success based on statusDesc
                    if ("SUCCESSFUL".equalsIgnoreCase(responseBody.getStatusDesc())) {
                        responseBody.setSuccess(true);
                    } else {
                        responseBody.setSuccess(false);
                    }
                    
                    logger.info("✅ Manually extracted BizaoPayment response fields - TransactionId: {}, Status: {}, StatusDesc: {}", 
                        responseBody.getTransactionId(), responseBody.getStatus(), responseBody.getStatusDesc());
                } catch (Exception parseException) {
                    logger.error("Failed to manually extract BizaoPayment response fields: {}", parseException.getMessage());
                    // Fallback: create basic response with raw message
                    responseBody = new BizaoPaymentResponse();
                    responseBody.setStatus(httpStatusCode);
                    responseBody.setSuccess(false);
                    responseBody.setMessage("Failed to parse response: " + responseBodyString);
                }
            }
            
            // Set HTTP status code in response if not already set from JSON body
            if (responseBody.getStatus() == null) {
                responseBody.setStatus(httpStatusCode);
            }
            
            // Log transaction ID if present
            if (responseBody.getTransactionId() != null && !responseBody.getTransactionId().trim().isEmpty()) {
                logger.info("✅ BizaoPayment transaction ID received: {}", responseBody.getTransactionId());
            } else {
                logger.warn("⚠️ BizaoPayment response does not contain transactionId. Status: {}, Success: {}, Response: {}", 
                    responseBody.getStatus(), responseBody.getSuccess(), responseBodyString);
            }
            
            return responseBody;
        } catch (HttpClientErrorException e) {
            // Handle HTTP client errors (4xx) - extract response body properly
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            
            logger.error("❌ BizaoPayment HTTP error - Status: {}, Response: {}", statusCode, responseBody);
            logger.error("BizaoPayment error details - URL: {}, Headers sent: Authorization={}", url, 
                bizaoPaymentApiToken != null && bizaoPaymentApiToken.length() > 10 ? bizaoPaymentApiToken.substring(0, 10) + "..." : "null");
            
            BizaoPaymentResponse errorResponse = new BizaoPaymentResponse();
            errorResponse.setStatus(statusCode);
            errorResponse.setSuccess(false);
            
            // Try to parse the error response body
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                BizaoPaymentResponse parsedError = objectMapper.readValue(responseBody, BizaoPaymentResponse.class);
                // Use errorMessage if available, otherwise use message
                String errorMsg = parsedError.getErrorMessage() != null ? parsedError.getErrorMessage() : parsedError.getMessage();
                errorResponse.setMessage(errorMsg);
                errorResponse.setTransactionId(parsedError.getTransactionId());
                logger.info("Parsed BizaoPayment error response - Status: {}, Message: {}, TransactionId: {}", 
                    statusCode, errorMsg, parsedError.getTransactionId());
            } catch (Exception parseException) {
                logger.warn("Failed to parse BizaoPayment error response, using raw body: {}", responseBody);
                logger.warn("Parse exception: {}", parseException.getMessage());
                // If parsing fails, use the raw response body as message
                errorResponse.setMessage(responseBody != null && !responseBody.isEmpty() ? responseBody : e.getMessage());
            }
            
            return errorResponse;
        } catch (Exception e) {
            logger.error("Error initiating BizaoPayment payment: ", e);
            BizaoPaymentResponse errorResponse = new BizaoPaymentResponse();
            errorResponse.setSuccess(false);
            errorResponse.setMessage("Failed to initiate payment: " + e.getMessage());
            return errorResponse;
        }
    }

    public BizaoPaymentResponse checkTransactionStatus(String transactionId) {
        String url = bizaoPaymentApiUrl + "/api/v1/momo/transactionstatus/" + transactionId;
        
        logger.info("Checking BizaoPayment transaction status - URL: {}, Transaction ID: {}", url, transactionId);
        
        HttpHeaders headers = new HttpHeaders();
        // BizaoPayment expects Authorization header with the token directly
        headers.set("Authorization", bizaoPaymentApiToken);
        logger.debug("BizaoPayment check status headers - Authorization: {}...", bizaoPaymentApiToken.substring(0, Math.min(10, bizaoPaymentApiToken.length())));

        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<BizaoPaymentResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    BizaoPaymentResponse.class
            );
            
            BizaoPaymentResponse responseBody = response.getBody();
            int httpStatusCode = response.getStatusCode().value();
            logger.info("BizaoPayment check status HTTP response: {}", httpStatusCode);
            logger.debug("BizaoPayment check status response body: {}", responseBody);
            
            if (responseBody == null) {
                logger.warn("BizaoPayment check status returned null response body");
                BizaoPaymentResponse errorResponse = new BizaoPaymentResponse();
                errorResponse.setStatus(httpStatusCode);
                errorResponse.setSuccess(false);
                errorResponse.setMessage("BizaoPayment check status returned null response");
                return errorResponse;
            }
            
            // Set HTTP status code in response if not already set from JSON body
            if (responseBody.getStatus() == null) {
                responseBody.setStatus(httpStatusCode);
            }
            
            return responseBody;
        } catch (HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            
            logger.error("BizaoPayment check status HTTP error - Status: {}, Response: {}", statusCode, responseBody);
            
            BizaoPaymentResponse errorResponse = new BizaoPaymentResponse();
            errorResponse.setStatus(statusCode);
            errorResponse.setSuccess(false);
            errorResponse.setMessage(responseBody != null && !responseBody.isEmpty() ? responseBody : e.getMessage());
            
            return errorResponse;
        } catch (Exception e) {
            logger.error("Error checking BizaoPayment transaction status: ", e);
            BizaoPaymentResponse errorResponse = new BizaoPaymentResponse();
            errorResponse.setSuccess(false);
            errorResponse.setMessage("Failed to check transaction status: " + e.getMessage());
            return errorResponse;
        }
    }

    /**
     * Get account holder information for a phone number
     * GET api/v1/momo/accountholder/information/{phone}
     * 
     * @param phone Phone number (e.g., "250794230137")
     * @return BizaoAccountHolderResponse with customer information
     */
    public BizaoAccountHolderResponse getAccountHolderInformation(String phone) {
        String url = bizaoPaymentApiUrl + "/api/v1/momo/accountholder/information/" + phone;
        
        logger.info("Getting BizaoPayment account holder information - URL: {}, Phone: {}", url, phone);
        
        HttpHeaders headers = new HttpHeaders();
        // BizaoPayment expects Authorization header with the token directly
        headers.set("Authorization", bizaoPaymentApiToken);
        logger.debug("BizaoPayment account holder info headers - Authorization: {}...", bizaoPaymentApiToken.substring(0, Math.min(10, bizaoPaymentApiToken.length())));

        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<BizaoAccountHolderResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    BizaoAccountHolderResponse.class
            );
            
            BizaoAccountHolderResponse responseBody = response.getBody();
            int httpStatusCode = response.getStatusCode().value();
            logger.info("BizaoPayment account holder info HTTP response: {}", httpStatusCode);
            logger.debug("BizaoPayment account holder info response body: {}", responseBody);
            
            if (responseBody == null) {
                logger.warn("BizaoPayment account holder info returned null response body");
                BizaoAccountHolderResponse errorResponse = new BizaoAccountHolderResponse();
                errorResponse.setStatus(httpStatusCode);
                return errorResponse;
            }
            
            // Set HTTP status code in response if not already set from JSON body
            if (responseBody.getStatus() == null) {
                responseBody.setStatus(httpStatusCode);
            }
            
            logger.info("BizaoPayment account holder info - Name: {}, Status: {}", responseBody.getFullName(), responseBody.getStatus());
            return responseBody;
        } catch (HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            
            logger.error("BizaoPayment account holder info HTTP error - Status: {}, Response: {}", statusCode, responseBody);
            
            BizaoAccountHolderResponse errorResponse = new BizaoAccountHolderResponse();
            errorResponse.setStatus(statusCode);
            
            return errorResponse;
        } catch (Exception e) {
            logger.error("Error getting BizaoPayment account holder information: ", e);
            BizaoAccountHolderResponse errorResponse = new BizaoAccountHolderResponse();
            errorResponse.setStatus(500);
            return errorResponse;
        }
    }
}
