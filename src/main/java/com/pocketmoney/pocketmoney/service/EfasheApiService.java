package com.pocketmoney.pocketmoney.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketmoney.pocketmoney.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class EfasheApiService {

    private static final Logger logger = LoggerFactory.getLogger(EfasheApiService.class);

    @Value("${efashe.api.url:https://sb-api.efashe.com/rw/v2}")
    private String efasheApiUrl;

    @Value("${efashe.api.key:6a66e55b-3c9c-4d0c-9a35-025c5def77fd}")
    private String efasheApiKey;

    @Value("${efashe.api.secret:c06200df-c55c-416d-b9af-43568b5abac2}")
    private String efasheApiSecret;

    private final RestTemplate restTemplate;
    
    // Token caching
    private String cachedAccessToken;
    private LocalDateTime tokenExpiresAt;

    public EfasheApiService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    
    /**
     * Authenticate with EFASHE API and get access token
     * POST /auth
     */
    private String getAccessToken() {
        // Check if we have a valid cached token
        if (cachedAccessToken != null && tokenExpiresAt != null && LocalDateTime.now().isBefore(tokenExpiresAt.minusMinutes(5))) {
            logger.debug("Using cached EFASHE access token (expires at: {})", tokenExpiresAt);
            return cachedAccessToken;
        }
        
        String authUrl = efasheApiUrl + "/auth";
        logger.info("Authenticating with EFASHE API to get access token");
        
        EfasheAuthRequest authRequest = new EfasheAuthRequest();
        authRequest.setApiKey(efasheApiKey);
        authRequest.setApiSecret(efasheApiSecret);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<EfasheAuthRequest> entity = new HttpEntity<>(authRequest, headers);
        
        try {
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    authUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            
            String responseBodyString = rawResponse.getBody();
            int httpStatusCode = rawResponse.getStatusCode().value();
            
            logger.info("EFASHE auth response - Status: {}", httpStatusCode);
            
            ObjectMapper objectMapper = new ObjectMapper();
            EfasheAuthResponse authResponse = objectMapper.readValue(responseBodyString, EfasheAuthResponse.class);
            
            if (authResponse.getData() != null && authResponse.getData().getAccessToken() != null) {
                cachedAccessToken = authResponse.getData().getAccessToken();
                
                // Parse expiration time
                String expiresAtStr = authResponse.getData().getAccessTokenExpiresAt();
                if (expiresAtStr != null && !expiresAtStr.isEmpty()) {
                    try {
                        // Parse ISO 8601 format: "2026-01-16T22:05:04.731871"
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSSSSS]");
                        tokenExpiresAt = LocalDateTime.parse(expiresAtStr, formatter);
                        logger.info("EFASHE access token obtained - Expires at: {}", tokenExpiresAt);
                    } catch (Exception e) {
                        logger.warn("Failed to parse token expiration time: {}, defaulting to 1 hour", expiresAtStr);
                        tokenExpiresAt = LocalDateTime.now().plusHours(1);
                    }
                } else {
                    // Default to 1 hour if expiration not provided
                    tokenExpiresAt = LocalDateTime.now().plusHours(1);
                }
                
                return cachedAccessToken;
            } else {
                throw new RuntimeException("EFASHE auth response did not contain access token");
            }
        } catch (HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            logger.error("EFASHE auth HTTP error - Status: {}, Response: {}", statusCode, responseBody);
            throw new RuntimeException("Failed to authenticate with EFASHE API (Status: " + statusCode + "): " + 
                (responseBody != null && !responseBody.isEmpty() ? responseBody : e.getMessage()));
        } catch (Exception e) {
            logger.error("Error authenticating with EFASHE API: ", e);
            throw new RuntimeException("Failed to authenticate with EFASHE API: " + e.getMessage());
        }
    }
    
    /**
     * Build HTTP headers with EFASHE API token authentication
     * Used for /vend/validate and /vend/execute
     */
    private HttpHeaders buildHeadersWithToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String token = getAccessToken();
        headers.set("Authorization", "Bearer " + token);
        return headers;
    }
    
    /**
     * Build HTTP headers without token (for poll status endpoint)
     */
    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Validate customer account with EFASHE API
     * POST /vend/validate
     * Uses token authentication
     */
    public EfasheValidateResponse validateAccount(EfasheValidateRequest request) {
        String url = efasheApiUrl + "/vend/validate";
        
        logger.info("Validating EFASHE account - Vertical: {}, Customer Account: {}", 
            request.getVerticalId(), request.getCustomerAccountNumber());
        
        HttpHeaders headers = buildHeadersWithToken();
        HttpEntity<EfasheValidateRequest> entity = new HttpEntity<>(request, headers);

        try {
            // Try to get raw response first to see if it's wrapped
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            
            String responseBodyString = rawResponse.getBody();
            int httpStatusCode = rawResponse.getStatusCode().value();
            
            logger.info("EFASHE validate raw response - Status: {}, Body: {}", httpStatusCode, responseBodyString);
            
            // Parse the response - might be wrapped in "data" object
            ObjectMapper objectMapper = new ObjectMapper();
            EfasheValidateResponse validateResponse;
            
            try {
                // Try to parse as direct response first
                validateResponse = objectMapper.readValue(responseBodyString, EfasheValidateResponse.class);
            } catch (Exception e) {
                // If that fails, try to extract from "data" wrapper
                logger.info("Response might be wrapped, trying to extract from 'data' field");
                JsonNode jsonNode = objectMapper.readTree(responseBodyString);
                if (jsonNode.has("data")) {
                    JsonNode dataNode = jsonNode.get("data");
                    // If data has another "data" nested, get that
                    if (dataNode.has("data")) {
                        validateResponse = objectMapper.treeToValue(dataNode.get("data"), EfasheValidateResponse.class);
                    } else {
                        validateResponse = objectMapper.treeToValue(dataNode, EfasheValidateResponse.class);
                    }
                } else {
                    validateResponse = objectMapper.treeToValue(jsonNode, EfasheValidateResponse.class);
                }
            }
            
            logger.info("EFASHE validate response parsed - TrxId: {}", 
                validateResponse != null ? validateResponse.getTrxId() : "N/A");
            
            return validateResponse;
        } catch (HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            logger.error("EFASHE validate HTTP error - Status: {}, Response: {}", statusCode, responseBody);
            
            // If 401, clear cached token and retry once
            if (statusCode == 401) {
                logger.warn("EFASHE token expired or invalid, clearing cache and retrying authentication");
                cachedAccessToken = null;
                tokenExpiresAt = null;
                
                // Retry once with new token
                try {
                    headers = buildHeadersWithToken();
                    entity = new HttpEntity<>(request, headers);
                    ResponseEntity<String> retryResponse = restTemplate.exchange(
                            url,
                            HttpMethod.POST,
                            entity,
                            String.class
                    );
                    return parseValidateResponse(retryResponse.getBody());
                } catch (Exception retryException) {
                    logger.error("EFASHE validate retry failed: ", retryException);
                    throw new RuntimeException("EFASHE API authentication failed after retry. Please verify API credentials.");
                }
            }
            
            throw new RuntimeException("Failed to validate EFASHE account (Status: " + statusCode + "): " + 
                (responseBody != null && !responseBody.isEmpty() ? responseBody : e.getMessage()));
        } catch (Exception e) {
            logger.error("Error validating EFASHE account: ", e);
            throw new RuntimeException("Failed to validate EFASHE account: " + e.getMessage());
        }
    }
    
    /**
     * Helper method to parse validate response
     */
    private EfasheValidateResponse parseValidateResponse(String responseBodyString) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            return objectMapper.readValue(responseBodyString, EfasheValidateResponse.class);
        } catch (Exception e) {
            JsonNode jsonNode = objectMapper.readTree(responseBodyString);
            if (jsonNode.has("data")) {
                JsonNode dataNode = jsonNode.get("data");
                if (dataNode.has("data")) {
                    return objectMapper.treeToValue(dataNode.get("data"), EfasheValidateResponse.class);
                } else {
                    return objectMapper.treeToValue(dataNode, EfasheValidateResponse.class);
                }
            } else {
                return objectMapper.treeToValue(jsonNode, EfasheValidateResponse.class);
            }
        }
    }

    /**
     * Execute EFASHE transaction
     * POST /vend/execute
     * Uses token authentication
     */
    public EfasheExecuteResponse executeTransaction(EfasheExecuteRequest request) {
        String url = efasheApiUrl + "/vend/execute";
        
        logger.info("Executing EFASHE transaction - TrxId: {}, Vertical: {}, Amount: {}, Customer: {}", 
            request.getTrxId(), request.getVerticalId(), request.getAmount(), request.getCustomerAccountNumber());
        
        HttpHeaders headers = buildHeadersWithToken();
        HttpEntity<EfasheExecuteRequest> entity = new HttpEntity<>(request, headers);

        try {
            // Get raw response to handle wrapped data object
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            
            String responseBodyString = rawResponse.getBody();
            int httpStatusCode = rawResponse.getStatusCode().value();
            
            logger.info("EFASHE execute raw response - Status: {}, Body: {}", httpStatusCode, responseBodyString);
            
            // Parse the response - wrapped in "data" object
            ObjectMapper objectMapper = new ObjectMapper();
            EfasheExecuteResponse executeResponse;
            
            try {
                JsonNode jsonNode = objectMapper.readTree(responseBodyString);
                if (jsonNode.has("data")) {
                    // Extract from "data" wrapper
                    JsonNode dataNode = jsonNode.get("data");
                    executeResponse = objectMapper.treeToValue(dataNode, EfasheExecuteResponse.class);
                    
                    // Try to extract token from data node if it's not in the response object
                    if (executeResponse != null && (executeResponse.getToken() == null || executeResponse.getToken().isEmpty())) {
                        if (dataNode.has("token")) {
                            executeResponse.setToken(dataNode.get("token").asText());
                            logger.info("Extracted token from data node: {}", executeResponse.getToken());
                        } else if (dataNode.has("extraInfo")) {
                            // Token might be in extraInfo
                            JsonNode extraInfo = dataNode.get("extraInfo");
                            if (extraInfo.has("token")) {
                                executeResponse.setToken(extraInfo.get("token").asText());
                                logger.info("Extracted token from extraInfo: {}", executeResponse.getToken());
                            }
                        }
                        // Also check if token is in message
                        if ((executeResponse.getToken() == null || executeResponse.getToken().isEmpty()) 
                            && executeResponse.getMessage() != null && executeResponse.getMessage().toLowerCase().contains("token")) {
                            logger.info("Token information may be in message field: {}", executeResponse.getMessage());
                        }
                    }
                    
                    logger.info("EFASHE execute response extracted from 'data' wrapper - PollEndpoint: {}, RetryAfterSecs: {}, Token: {}", 
                        executeResponse != null ? executeResponse.getPollEndpoint() : "N/A",
                        executeResponse != null ? executeResponse.getRetryAfterSecs() : "N/A",
                        executeResponse != null && executeResponse.getToken() != null ? executeResponse.getToken() : "N/A");
                } else {
                    // Try direct parsing
                    executeResponse = objectMapper.treeToValue(jsonNode, EfasheExecuteResponse.class);
                    
                    // Try to extract token if not in response object
                    if (executeResponse != null && (executeResponse.getToken() == null || executeResponse.getToken().isEmpty())) {
                        if (jsonNode.has("token")) {
                            executeResponse.setToken(jsonNode.get("token").asText());
                            logger.info("Extracted token from root node: {}", executeResponse.getToken());
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error parsing EFASHE execute response: ", e);
                throw new RuntimeException("Failed to parse EFASHE execute response: " + e.getMessage());
            }
            
            logger.info("EFASHE execute response parsed - PollEndpoint: {}, RetryAfterSecs: {}, Status: {}", 
                executeResponse != null ? executeResponse.getPollEndpoint() : "N/A",
                executeResponse != null ? executeResponse.getRetryAfterSecs() : "N/A",
                executeResponse != null ? executeResponse.getStatus() : "N/A");
            
            // Store HTTP status code in response for checking in service layer
            if (executeResponse != null) {
                executeResponse.setHttpStatusCode(httpStatusCode);
            }
            
            return executeResponse;
        } catch (HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            logger.error("EFASHE execute HTTP error - Status: {}, Response: {}", statusCode, responseBody);
            
            // If 401, clear cached token and retry once
            if (statusCode == 401) {
                logger.warn("EFASHE token expired or invalid, clearing cache and retrying authentication");
                cachedAccessToken = null;
                tokenExpiresAt = null;
                
                // Retry once with new token
                try {
                    headers = buildHeadersWithToken();
                    entity = new HttpEntity<>(request, headers);
                    ResponseEntity<String> retryResponse = restTemplate.exchange(
                            url,
                            HttpMethod.POST,
                            entity,
                            String.class
                    );
                    EfasheExecuteResponse retryExecuteResponse = parseExecuteResponse(retryResponse.getBody());
                    // Set HTTP status code from retry response
                    if (retryExecuteResponse != null) {
                        retryExecuteResponse.setHttpStatusCode(retryResponse.getStatusCode().value());
                    }
                    return retryExecuteResponse;
                } catch (Exception retryException) {
                    logger.error("EFASHE execute retry failed: ", retryException);
                    throw new RuntimeException("EFASHE API authentication failed after retry. Please verify API credentials.");
                }
            }
            
            throw new RuntimeException("Failed to execute EFASHE transaction (Status: " + statusCode + "): " + 
                (responseBody != null && !responseBody.isEmpty() ? responseBody : e.getMessage()));
        } catch (Exception e) {
            logger.error("Error executing EFASHE transaction: ", e);
            throw new RuntimeException("Failed to execute EFASHE transaction: " + e.getMessage());
        }
    }
    
    /**
     * Helper method to parse execute response
     */
    private EfasheExecuteResponse parseExecuteResponse(String responseBodyString) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBodyString);
        if (jsonNode.has("data")) {
            JsonNode dataNode = jsonNode.get("data");
            return objectMapper.treeToValue(dataNode, EfasheExecuteResponse.class);
        } else {
            return objectMapper.treeToValue(jsonNode, EfasheExecuteResponse.class);
        }
    }

    /**
     * Poll EFASHE transaction status using the poll endpoint
     * GET /vend/{trxId}/status
     * Uses token authentication (Bearer token)
     */
    public EfashePollStatusResponse pollTransactionStatus(String pollEndpoint) {
        // pollEndpoint should be relative path like "/vend/23oMSt0cODJPa07PH0AOdpS73u6/status"
        // or "/v2/trx/849d5438-fe49-4c11-b959-dac800d187dd/status" or full URL
        // NOTE: efasheApiUrl is "https://sb-api.efashe.com/rw/v2"
        // For /vend/ endpoints, we need to use /rw/vend/ instead of /rw/v2/vend/
        // Also remove trailing slash if present
        String url;
        if (pollEndpoint == null || pollEndpoint.trim().isEmpty()) {
            throw new RuntimeException("Poll endpoint cannot be null or empty");
        }
        
        // Remove trailing slash if present
        String cleanPollEndpoint = pollEndpoint.trim();
        if (cleanPollEndpoint.endsWith("/")) {
            cleanPollEndpoint = cleanPollEndpoint.substring(0, cleanPollEndpoint.length() - 1);
        }
        
        if (cleanPollEndpoint.startsWith("http")) {
            url = cleanPollEndpoint;
        } else if (cleanPollEndpoint.startsWith("/")) {
            // Handle different poll endpoint formats
            String cleanEndpoint = cleanPollEndpoint;
            
            if (cleanPollEndpoint.startsWith("/vend/")) {
                // pollEndpoint is like "/vend/23oMSt0cODJPa07PH0AOdpS73u6/status"
                // efasheApiUrl is "https://sb-api.efashe.com/rw/v2"
                // We need "https://sb-api.efashe.com/rw/vend/23oMSt0cODJPa07PH0AOdpS73u6/status"
                // Replace "/rw/v2" with "/rw" and append the endpoint
                String baseUrl = efasheApiUrl.replace("/v2", ""); // Remove "/v2" -> "https://sb-api.efashe.com/rw"
                url = baseUrl + cleanEndpoint;
            } else if (cleanPollEndpoint.startsWith("/v2/")) {
                // pollEndpoint is like "/v2/trx/...", so we need "/trx/..." instead
                // efasheApiUrl is "https://sb-api.efashe.com/rw/v2"
                cleanEndpoint = cleanPollEndpoint.substring(3); // Remove "/v2" -> "/trx/..."
                url = efasheApiUrl + cleanEndpoint;
            } else {
                // Other relative paths - append directly
                url = efasheApiUrl + cleanEndpoint;
            }
        } else {
            url = efasheApiUrl + "/" + cleanPollEndpoint;
        }
        
        logger.info("Polling EFASHE transaction status - Endpoint: {}", url);
        
        // Poll status endpoint requires token authentication (same as validate and execute)
        // Use headers with Bearer token for authentication
        HttpHeaders headers = buildHeadersWithToken();
        logger.info("EFASHE poll status - Using Bearer token authentication");
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            // Get raw response to handle wrapped data object
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );
            
            String responseBodyString = rawResponse.getBody();
            int httpStatusCode = rawResponse.getStatusCode().value();
            
            logger.info("EFASHE poll status raw response - Status: {}, Body: {}", httpStatusCode, responseBodyString);
            
            // Parse the response - might be wrapped in "data" object
            ObjectMapper objectMapper = new ObjectMapper();
            EfashePollStatusResponse pollResponse;
            
            try {
                JsonNode jsonNode = objectMapper.readTree(responseBodyString);
                JsonNode dataNodeToUse = null;
                
                if (jsonNode.has("data")) {
                    // Extract from "data" wrapper
                    JsonNode dataNode = jsonNode.get("data");
                    // If data has another "data" nested, get that
                    if (dataNode.has("data")) {
                        dataNodeToUse = dataNode.get("data");
                        pollResponse = objectMapper.treeToValue(dataNodeToUse, EfashePollStatusResponse.class);
                    } else {
                        dataNodeToUse = dataNode;
                        pollResponse = objectMapper.treeToValue(dataNodeToUse, EfashePollStatusResponse.class);
                    }
                } else {
                    dataNodeToUse = jsonNode;
                    pollResponse = objectMapper.treeToValue(dataNodeToUse, EfashePollStatusResponse.class);
                }
                
                // Extract status from trxStatusId if status is null
                if (pollResponse != null && (pollResponse.getStatus() == null || pollResponse.getStatus().isEmpty())) {
                    if (dataNodeToUse != null && dataNodeToUse.has("trxStatusId")) {
                        String trxStatusId = dataNodeToUse.get("trxStatusId").asText();
                        // Map trxStatusId values to status values
                        if ("successful".equalsIgnoreCase(trxStatusId)) {
                            pollResponse.setStatus("SUCCESS");
                        } else if ("failed".equalsIgnoreCase(trxStatusId)) {
                            pollResponse.setStatus("FAILED");
                        } else if ("pending".equalsIgnoreCase(trxStatusId)) {
                            pollResponse.setStatus("PENDING");
                        } else {
                            pollResponse.setStatus(trxStatusId.toUpperCase());
                        }
                        logger.info("Extracted status from trxStatusId: {} -> {}", trxStatusId, pollResponse.getStatus());
                    }
                }
                
                // Extract token if available
                if (pollResponse != null && (pollResponse.getToken() == null || pollResponse.getToken().isEmpty())) {
                    if (dataNodeToUse != null && dataNodeToUse.has("token")) {
                        pollResponse.setToken(dataNodeToUse.get("token").asText());
                        logger.info("Extracted token from data node: {}", pollResponse.getToken());
                    }
                }
            } catch (Exception e) {
                logger.error("Error parsing EFASHE poll status response: ", e);
                throw new RuntimeException("Failed to parse EFASHE poll status response: " + e.getMessage());
            }
            
            logger.info("EFASHE poll status response parsed - Status: '{}', TrxId: {}, Message: {}", 
                pollResponse != null ? pollResponse.getStatus() : "N/A",
                pollResponse != null ? pollResponse.getTrxId() : "N/A",
                pollResponse != null ? pollResponse.getMessage() : "N/A");
            
            // Log the raw status value for debugging
            if (pollResponse != null && pollResponse.getStatus() != null) {
                logger.info("EFASHE poll status raw value: '{}' (length: {}, equals SUCCESS: {})", 
                    pollResponse.getStatus(), 
                    pollResponse.getStatus().length(),
                    "SUCCESS".equalsIgnoreCase(pollResponse.getStatus().trim()));
            }
            
            return pollResponse;
        } catch (HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            logger.error("EFASHE poll status HTTP error - Status: {}, Response: {}", statusCode, responseBody);
            
            // If 401, clear cached token and retry once
            if (statusCode == 401) {
                logger.warn("EFASHE token expired or invalid for poll status, clearing cache and retrying authentication");
                cachedAccessToken = null;
                tokenExpiresAt = null;
                
                // Retry once with new token
                try {
                    HttpHeaders retryHeaders = buildHeadersWithToken();
                    HttpEntity<String> retryEntity = new HttpEntity<>(retryHeaders);
                    ResponseEntity<String> retryResponse = restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            retryEntity,
                            String.class
                    );
                    
                    // Parse retry response
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode jsonNode = objectMapper.readTree(retryResponse.getBody());
                    EfashePollStatusResponse pollResponse;
                    if (jsonNode.has("data")) {
                        JsonNode dataNode = jsonNode.get("data");
                        if (dataNode.has("data")) {
                            pollResponse = objectMapper.treeToValue(dataNode.get("data"), EfashePollStatusResponse.class);
                        } else {
                            pollResponse = objectMapper.treeToValue(dataNode, EfashePollStatusResponse.class);
                        }
                    } else {
                        pollResponse = objectMapper.treeToValue(jsonNode, EfashePollStatusResponse.class);
                    }
                    return pollResponse;
                } catch (Exception retryException) {
                    logger.error("EFASHE poll status retry failed: ", retryException);
                    throw new RuntimeException("EFASHE API authentication failed after retry. Please verify API credentials.");
                }
            }
            
            // If 404, try refreshing the token and retry once (endpoint might be valid but token expired)
            if (statusCode == 404) {
                logger.warn("EFASHE poll endpoint returned 404 - refreshing token and retrying (endpoint may be valid but token expired): {}", url);
                // Clear cached token to force refresh
                cachedAccessToken = null;
                tokenExpiresAt = null;
                
                // Retry once with new token (like we do for 401)
                try {
                    HttpHeaders retryHeaders = buildHeadersWithToken();
                    HttpEntity<String> retryEntity = new HttpEntity<>(retryHeaders);
                    ResponseEntity<String> retryResponse = restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            retryEntity,
                            String.class
                    );
                    
                    // Parse retry response
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode jsonNode = objectMapper.readTree(retryResponse.getBody());
                    EfashePollStatusResponse pollResponse;
                    if (jsonNode.has("data")) {
                        JsonNode dataNode = jsonNode.get("data");
                        if (dataNode.has("data")) {
                            pollResponse = objectMapper.treeToValue(dataNode.get("data"), EfashePollStatusResponse.class);
                        } else {
                            pollResponse = objectMapper.treeToValue(dataNode, EfashePollStatusResponse.class);
                        }
                    } else {
                        pollResponse = objectMapper.treeToValue(jsonNode, EfashePollStatusResponse.class);
                    }
                    logger.info("✅ EFASHE poll retry with refreshed token succeeded after 404");
                    return pollResponse;
                } catch (Exception retryException) {
                    logger.error("EFASHE poll status retry after 404 failed: ", retryException);
                    // If retry still fails with 404, then the endpoint is truly expired
                    if (retryException instanceof HttpClientErrorException) {
                        HttpClientErrorException httpException = (HttpClientErrorException) retryException;
                        if (httpException.getStatusCode().value() == 404) {
                            throw new RuntimeException("EFASHE_POLL_ENDPOINT_NOT_FOUND: Poll endpoint returned 404 even after token refresh. The transaction status endpoint is no longer available. This may indicate the transaction has expired or the endpoint is invalid.");
                        }
                    }
                    throw new RuntimeException("EFASHE poll endpoint retry failed after 404: " + retryException.getMessage());
                }
            }
            
            throw new RuntimeException("Failed to poll EFASHE transaction status (Status: " + statusCode + "): " + 
                (responseBody != null && !responseBody.isEmpty() ? responseBody : e.getMessage()));
        } catch (Exception e) {
            logger.error("Error polling EFASHE transaction status: ", e);
            throw new RuntimeException("Failed to poll EFASHE transaction status: " + e.getMessage());
        }
    }
    
    /**
     * Get list of verticals from EFASHE API
     * GET /verticals or /vend/verticals
     * Uses token authentication
     */
    public Object getVerticals() {
        // Try common EFASHE API endpoints for verticals
        String[] possibleEndpoints = {
            efasheApiUrl + "/verticals",
            efasheApiUrl + "/vend/verticals",
            efasheApiUrl + "/verticals/list"
        };
        
        HttpHeaders headers = buildHeadersWithToken();
        
        for (String url : possibleEndpoints) {
            try {
                logger.info("Attempting to fetch verticals from: {}", url);
                HttpEntity<String> entity = new HttpEntity<>(headers);
                
                ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
                );
                
                String responseBody = response.getBody();
                int httpStatusCode = response.getStatusCode().value();
                
                logger.info("EFASHE verticals response - Status: {}, URL: {}", httpStatusCode, url);
                
                if (httpStatusCode == 200 && responseBody != null) {
                    // Parse and return the response
                    ObjectMapper objectMapper = new ObjectMapper();
                    try {
                        // Try to parse as JSON
                        JsonNode jsonNode = objectMapper.readTree(responseBody);
                        
                        // Handle wrapped response in "data" field
                        if (jsonNode.has("data")) {
                            return objectMapper.convertValue(jsonNode.get("data"), Object.class);
                        }
                        
                        return objectMapper.convertValue(jsonNode, Object.class);
                    } catch (Exception e) {
                        logger.warn("Failed to parse verticals response as JSON, returning raw string");
                        return responseBody;
                    }
                }
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 404) {
                    // Endpoint doesn't exist, try next one
                    logger.debug("Endpoint {} returned 404, trying next...", url);
                    continue;
                }
                
                // For other HTTP errors, log and continue to next endpoint
                logger.warn("Error fetching verticals from {}: Status: {}, Message: {}", 
                    url, e.getStatusCode().value(), e.getMessage());
                continue;
            } catch (Exception e) {
                logger.warn("Exception fetching verticals from {}: {}", url, e.getMessage());
                continue;
            }
        }
        
        // If all endpoints failed, throw error
        throw new RuntimeException("Failed to fetch verticals from EFASHE API. Tried multiple endpoints but none returned valid data.");
    }
    
    /**
     * Get electricity tokens for a meter number
     * GET /electricity/tokens?meterNo={meterNumber}&numTokens={numTokens}
     * Uses token authentication
     */
    public ElectricityTokensResponse getElectricityTokens(String meterNumber, Integer numTokens) {
        // Build URL with meter number and numTokens as query parameters
        // Default numTokens to 1 if not provided
        if (numTokens == null || numTokens <= 0) {
            numTokens = 1;
        }
        String url = efasheApiUrl + "/electricity/tokens?meterNo=" + meterNumber + "&numTokens=" + numTokens;
        
        logger.info("Fetching electricity tokens for meter number: {}", meterNumber);
        
        HttpHeaders headers = buildHeadersWithToken();
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );
            
            String responseBodyString = rawResponse.getBody();
            int httpStatusCode = rawResponse.getStatusCode().value();
            
            logger.info("EFASHE electricity tokens raw response - Status: {}, Body: {}", httpStatusCode, responseBodyString);
            
            // Parse the response
            ObjectMapper objectMapper = new ObjectMapper();
            ElectricityTokensResponse tokensResponse;
            
            try {
                JsonNode jsonNode = objectMapper.readTree(responseBodyString);
                
                // Handle wrapped response in "data" field
                if (jsonNode.has("data")) {
                    tokensResponse = objectMapper.treeToValue(jsonNode, ElectricityTokensResponse.class);
                } else {
                    // If response is directly the data array, wrap it
                    tokensResponse = new ElectricityTokensResponse();
                    tokensResponse.setData(objectMapper.convertValue(jsonNode, 
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ElectricityTokensResponse.ElectricityTokenData.class)));
                    tokensResponse.setStatus(httpStatusCode);
                }
            } catch (Exception e) {
                logger.error("Error parsing electricity tokens response: ", e);
                throw new RuntimeException("Failed to parse electricity tokens response: " + e.getMessage());
            }
            
            if (tokensResponse != null && tokensResponse.getData() != null && !tokensResponse.getData().isEmpty()) {
                ElectricityTokensResponse.ElectricityTokenData firstToken = tokensResponse.getData().get(0);
                logger.info("Electricity tokens retrieved - First token: {}, Meter: {}, Units: {}", 
                    firstToken.getToken() != null ? firstToken.getToken() : "N/A",
                    firstToken.getMeterno() != null ? firstToken.getMeterno() : "N/A",
                    firstToken.getUnits() != null ? firstToken.getUnits() : "N/A");
            } else {
                logger.warn("Electricity tokens response is empty or null for meter: {}", meterNumber);
            }
            
            return tokensResponse;
        } catch (HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            logger.error("EFASHE electricity tokens HTTP error - Status: {}, Response: {}", statusCode, responseBody);
            
            // If 401, clear cached token and retry once
            if (statusCode == 401) {
                logger.warn("EFASHE token expired or invalid for electricity tokens, clearing cache and retrying authentication");
                cachedAccessToken = null;
                tokenExpiresAt = null;
                
                // Retry once with new token
                try {
                    HttpHeaders retryHeaders = buildHeadersWithToken();
                    HttpEntity<String> retryEntity = new HttpEntity<>(retryHeaders);
                    ResponseEntity<String> retryResponse = restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            retryEntity,
                            String.class
                    );
                    
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode jsonNode = objectMapper.readTree(retryResponse.getBody());
                    ElectricityTokensResponse tokensResponse;
                    if (jsonNode.has("data")) {
                        tokensResponse = objectMapper.treeToValue(jsonNode, ElectricityTokensResponse.class);
                    } else {
                        tokensResponse = new ElectricityTokensResponse();
                        tokensResponse.setData(objectMapper.convertValue(jsonNode, 
                            objectMapper.getTypeFactory().constructCollectionType(List.class, ElectricityTokensResponse.ElectricityTokenData.class)));
                        tokensResponse.setStatus(retryResponse.getStatusCode().value());
                    }
                    return tokensResponse;
                } catch (Exception retryException) {
                    logger.error("EFASHE electricity tokens retry failed: ", retryException);
                    throw new RuntimeException("EFASHE API authentication failed after retry. Please verify API credentials.");
                }
            }
            
            throw new RuntimeException("Failed to get electricity tokens (Status: " + statusCode + "): " + 
                (responseBody != null && !responseBody.isEmpty() ? responseBody : e.getMessage()));
        } catch (Exception e) {
            logger.error("Error getting electricity tokens: ", e);
            throw new RuntimeException("Failed to get electricity tokens: " + e.getMessage());
        }
    }
}
