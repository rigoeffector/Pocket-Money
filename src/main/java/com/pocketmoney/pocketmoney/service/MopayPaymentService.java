package com.pocketmoney.pocketmoney.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketmoney.pocketmoney.dto.MopayECWPaymentInitiateRequest;
import com.pocketmoney.pocketmoney.dto.MopayECWPaymentResponse;
import com.pocketmoney.pocketmoney.dto.MopayECWAccountHolderResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service
public class MopayPaymentService {

    private static final Logger logger = LoggerFactory.getLogger(MopayPaymentService.class);

    @Value("${mopay.api.url:http://41.186.14.66:443}")
    private String mopayPaymentApiUrl;

    @Value("${mopay.api.token:dW5vdGlmeTpRMG5XMUMhLkBEM1YjJTgqTTIxMkBf}")
    private String mopayPaymentApiToken;

    private final RestTemplate restTemplate;

    public MopayPaymentService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public MopayECWPaymentResponse initiatePayment(MopayECWPaymentInitiateRequest request) {
        String url = mopayPaymentApiUrl + "/api/v1/payment";
        
        logger.info("Initiating MopayECW payment to: {}", url);
        logger.info("MopayECW request - Account No: {}, Amount: {}, Currency: {}, Title: {}", 
            request.getAccount_no(), request.getAmount(), request.getCurrency(), request.getTitle());
        
        if (request.getTransfers() != null && !request.getTransfers().isEmpty()) {
            for (int i = 0; i < request.getTransfers().size(); i++) {
                MopayECWPaymentInitiateRequest.Transfer transfer = request.getTransfers().get(i);
                logger.info("Transfer #{} - Account No: {}, Amount: {}, Message: {}", 
                    i + 1, transfer.getAccount_no(), transfer.getAmount(), transfer.getMessage());
            }
        }
        logger.debug("Full MopayECW request: {}", request);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // MopayECW expects Authorization header with the token directly
        headers.set("Authorization", mopayPaymentApiToken);
        logger.debug("MopayECW request headers - Authorization: {}...", mopayPaymentApiToken.substring(0, Math.min(10, mopayPaymentApiToken.length())));

        HttpEntity<MopayECWPaymentInitiateRequest> entity = new HttpEntity<>(request, headers);

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
            
            logger.info("MopayECW HTTP response status: {}", httpStatusCode);
            logger.info("MopayECW raw response body: {}", responseBodyString);
            
            if (responseBodyString == null || responseBodyString.trim().isEmpty()) {
                logger.warn("MopayECW returned null or empty response body");
                MopayECWPaymentResponse errorResponse = new MopayECWPaymentResponse();
                errorResponse.setStatus(httpStatusCode);
                errorResponse.setSuccess(false);
                errorResponse.setMessage("MopayECW returned null or empty response");
                return errorResponse;
            }
            
            // Try to parse the response
            ObjectMapper objectMapper = new ObjectMapper();
            MopayECWPaymentResponse responseBody;
            
            try {
                // First, try to parse as direct MopayECW response
                responseBody = objectMapper.readValue(responseBodyString, MopayECWPaymentResponse.class);
                
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
                logger.error("Failed to parse MopayECW response: {}", e.getMessage());
                // Try to manually extract fields from the JSON string
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> responseMap = objectMapper.readValue(responseBodyString, java.util.Map.class);
                    responseBody = new MopayECWPaymentResponse();
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
                    
                    logger.info("✅ Manually extracted MopayECW response fields - TransactionId: {}, Status: {}, StatusDesc: {}", 
                        responseBody.getTransactionId(), responseBody.getStatus(), responseBody.getStatusDesc());
                } catch (Exception parseException) {
                    logger.error("Failed to manually extract MopayECW response fields: {}", parseException.getMessage());
                    // Fallback: create basic response with raw message
                    responseBody = new MopayECWPaymentResponse();
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
                logger.info("✅ MopayECW transaction ID received: {}", responseBody.getTransactionId());
            } else {
                logger.warn("⚠️ MopayECW response does not contain transactionId. Status: {}, Success: {}, Response: {}", 
                    responseBody.getStatus(), responseBody.getSuccess(), responseBodyString);
            }
            
            return responseBody;
        } catch (HttpClientErrorException e) {
            // Handle HTTP client errors (4xx) - extract response body properly
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            
            logger.error("❌ MopayECW HTTP error - Status: {}, Response: {}", statusCode, responseBody);
            logger.error("MopayECW error details - URL: {}, Headers sent: Authorization={}", url, 
                mopayPaymentApiToken != null && mopayPaymentApiToken.length() > 10 ? mopayPaymentApiToken.substring(0, 10) + "..." : "null");
            
            MopayECWPaymentResponse errorResponse = new MopayECWPaymentResponse();
            errorResponse.setStatus(statusCode);
            errorResponse.setSuccess(false);
            
            // Try to parse the error response body
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                MopayECWPaymentResponse parsedError = objectMapper.readValue(responseBody, MopayECWPaymentResponse.class);
                // Use errorMessage if available, otherwise use message
                String errorMsg = parsedError.getErrorMessage() != null ? parsedError.getErrorMessage() : parsedError.getMessage();
                errorResponse.setMessage(errorMsg);
                errorResponse.setTransactionId(parsedError.getTransactionId());
                logger.info("Parsed MopayECW error response - Status: {}, Message: {}, TransactionId: {}", 
                    statusCode, errorMsg, parsedError.getTransactionId());
            } catch (Exception parseException) {
                logger.warn("Failed to parse MopayECW error response, using raw body: {}", responseBody);
                logger.warn("Parse exception: {}", parseException.getMessage());
                // If parsing fails, use the raw response body as message
                errorResponse.setMessage(responseBody != null && !responseBody.isEmpty() ? responseBody : e.getMessage());
            }
            
            return errorResponse;
        } catch (Exception e) {
            logger.error("Error initiating MopayECW payment: ", e);
            MopayECWPaymentResponse errorResponse = new MopayECWPaymentResponse();
            errorResponse.setSuccess(false);
            errorResponse.setMessage("Failed to initiate payment: " + e.getMessage());
            return errorResponse;
        }
    }

    public MopayECWPaymentResponse checkTransactionStatus(String transactionId) {
        String url = mopayPaymentApiUrl + "/api/v1/momo/transactionstatus/" + transactionId;
        
        logger.info("Checking MopayECW transaction status - URL: {}, Transaction ID: {}", url, transactionId);
        
        HttpHeaders headers = new HttpHeaders();
        // MopayECW expects Authorization header with the token directly
        headers.set("Authorization", mopayPaymentApiToken);
        logger.debug("MopayECW check status headers - Authorization: {}...", mopayPaymentApiToken.substring(0, Math.min(10, mopayPaymentApiToken.length())));

        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<MopayECWPaymentResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    MopayECWPaymentResponse.class
            );
            
            MopayECWPaymentResponse responseBody = response.getBody();
            int httpStatusCode = response.getStatusCode().value();
            logger.info("MopayECW check status HTTP response: {}", httpStatusCode);
            logger.debug("MopayECW check status response body: {}", responseBody);
            
            if (responseBody == null) {
                logger.warn("MopayECW check status returned null response body");
                MopayECWPaymentResponse errorResponse = new MopayECWPaymentResponse();
                errorResponse.setStatus(httpStatusCode);
                errorResponse.setSuccess(false);
                errorResponse.setMessage("MopayECW check status returned null response");
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
            
            logger.error("MopayECW check status HTTP error - Status: {}, Response: {}", statusCode, responseBody);
            
            MopayECWPaymentResponse errorResponse = new MopayECWPaymentResponse();
            errorResponse.setStatus(statusCode);
            errorResponse.setSuccess(false);
            errorResponse.setMessage(responseBody != null && !responseBody.isEmpty() ? responseBody : e.getMessage());
            
            return errorResponse;
        } catch (Exception e) {
            logger.error("Error checking MopayECW transaction status: ", e);
            MopayECWPaymentResponse errorResponse = new MopayECWPaymentResponse();
            errorResponse.setSuccess(false);
            errorResponse.setMessage("Failed to check transaction status: " + e.getMessage());
            return errorResponse;
        }
    }

    /**
     * Get account holder information for a phone number
     * Uses /information endpoint which returns name (firstname, lastname)
     * Note: /identification endpoint only returns NID, not names
     * GET api/v1/momo/accountholder/information/{phone} - returns name
     * 
     * @param phone Phone number (e.g., "250794230137")
     * @return MopayECWAccountHolderResponse with customer information
     */
    public MopayECWAccountHolderResponse getAccountHolderInformation(String phone) {
        // Use /information endpoint (returns name information: firstname, lastname)
        String url = mopayPaymentApiUrl + "/api/v1/momo/accountholder/information/" + phone;
        
        logger.info("Getting MopayECW account holder information - URL: {}, Phone: {}", url, phone);
        
        HttpHeaders headers = new HttpHeaders();
        // MopayECW expects Authorization header with the token directly
        headers.set("Authorization", mopayPaymentApiToken);
        logger.debug("MopayECW account holder info headers - Authorization: {}...", mopayPaymentApiToken.substring(0, Math.min(10, mopayPaymentApiToken.length())));

        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            // First get raw response to log what we actually receive
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );
            
            int httpStatusCode = rawResponse.getStatusCode().value();
            String rawResponseBody = rawResponse.getBody();
            logger.info("MopayECW account holder info HTTP response: {}", httpStatusCode);
            logger.info("MopayECW account holder info RAW response body: {}", rawResponseBody);
            
            if (rawResponseBody == null || rawResponseBody.trim().isEmpty()) {
                logger.warn("MopayECW account holder info returned null or empty response body");
                MopayECWAccountHolderResponse errorResponse = new MopayECWAccountHolderResponse();
                errorResponse.setStatus(httpStatusCode);
                return errorResponse;
            }
            
            // Parse JSON response
            ObjectMapper objectMapper = new ObjectMapper();
            MopayECWAccountHolderResponse responseBody;
            try {
                responseBody = objectMapper.readValue(rawResponseBody, MopayECWAccountHolderResponse.class);
            } catch (Exception e) {
                logger.error("Failed to parse MopayECW account holder response JSON: {}", e.getMessage());
                logger.error("Raw response was: {}", rawResponseBody);
                MopayECWAccountHolderResponse errorResponse = new MopayECWAccountHolderResponse();
                errorResponse.setStatus(httpStatusCode);
                return errorResponse;
            }
            
            // Set HTTP status code in response if not already set from JSON body
            if (responseBody.getStatus() == null) {
                responseBody.setStatus(httpStatusCode);
            }
            
            // Log detailed information about the response
            logger.info("MopayECW account holder info parsed - Status: {}, Firstname: '{}', Lastname: '{}', FullName: '{}'", 
                responseBody.getStatus(), responseBody.getFirstname(), responseBody.getLastname(), responseBody.getFullName());
            
            return responseBody;
        } catch (HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            
            logger.error("MopayECW account holder info HTTP error - Status: {}, Response: {}", statusCode, responseBody);
            
            MopayECWAccountHolderResponse errorResponse = new MopayECWAccountHolderResponse();
            errorResponse.setStatus(statusCode);
            
            return errorResponse;
        } catch (Exception e) {
            logger.error("Error getting MopayECW account holder information: ", e);
            MopayECWAccountHolderResponse errorResponse = new MopayECWAccountHolderResponse();
            errorResponse.setStatus(500);
            return errorResponse;
        }
    }
}
