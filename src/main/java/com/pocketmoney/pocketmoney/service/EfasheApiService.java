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
        // First authenticate with EFASHE API to get access token
        String token = getAccessToken();
        logger.debug("Using EFASHE access token for API call");
        headers.set("Authorization", "Bearer " + token); // EFASHE API requires "Bearer " prefix
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
                    logger.info("EFASHE execute response extracted from 'data' wrapper - PollEndpoint: {}, RetryAfterSecs: {}", 
                        executeResponse != null ? executeResponse.getPollEndpoint() : "N/A",
                        executeResponse != null ? executeResponse.getRetryAfterSecs() : "N/A");
                } else {
                    // Try direct parsing
                    executeResponse = objectMapper.treeToValue(jsonNode, EfasheExecuteResponse.class);
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
        // pollEndpoint should be relative path like "/v2/trx/849d5438-fe49-4c11-b959-dac800d187dd/status/"
        // or full URL
        // NOTE: efasheApiUrl already ends with "/rw/v2", so if pollEndpoint starts with "/v2/",
        // we need to remove the duplicate "/v2" prefix
        String url;
        if (pollEndpoint.startsWith("http")) {
            url = pollEndpoint;
        } else if (pollEndpoint.startsWith("/")) {
            // Remove leading slash if pollEndpoint starts with "/v2/" to avoid duplicate
            String cleanEndpoint = pollEndpoint;
            if (pollEndpoint.startsWith("/v2/")) {
                // efasheApiUrl is like "https://sb-api.efashe.com/rw/v2"
                // pollEndpoint is like "/v2/trx/...", so we need "/trx/..." instead
                cleanEndpoint = pollEndpoint.substring(3); // Remove "/v2" -> "/trx/..."
            }
            url = efasheApiUrl + cleanEndpoint;
        } else {
            url = efasheApiUrl + "/" + pollEndpoint;
        }
        
        logger.info("Polling EFASHE transaction status - Endpoint: {}", url);
        
        // Poll status endpoint does NOT require token authentication (as per user's previous note)
        // Use headers without token
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
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
                if (jsonNode.has("data")) {
                    // Extract from "data" wrapper
                    JsonNode dataNode = jsonNode.get("data");
                    // If data has another "data" nested, get that
                    if (dataNode.has("data")) {
                        pollResponse = objectMapper.treeToValue(dataNode.get("data"), EfashePollStatusResponse.class);
                    } else {
                        pollResponse = objectMapper.treeToValue(dataNode, EfashePollStatusResponse.class);
                    }
                } else {
                    pollResponse = objectMapper.treeToValue(jsonNode, EfashePollStatusResponse.class);
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
            
            throw new RuntimeException("Failed to poll EFASHE transaction status (Status: " + statusCode + "): " + 
                (responseBody != null && !responseBody.isEmpty() ? responseBody : e.getMessage()));
        } catch (Exception e) {
            logger.error("Error polling EFASHE transaction status: ", e);
            throw new RuntimeException("Failed to poll EFASHE transaction status: " + e.getMessage());
        }
    }
    
    /**
     * Get electricity tokens after successful execution
     * GET /electricity/tokens?meterNo={meterNo}&numTokens={numTokens}
     * Uses token authentication
     * Only applicable for ELECTRICITY service type
     */
    public ElectricityTokenResponse getElectricityTokens(String meterNo, Integer numTokens) {
        String url = efasheApiUrl + "/electricity/tokens?meterNo=" + meterNo + "&numTokens=" + (numTokens != null ? numTokens : 1);
        
        logger.info("Getting electricity tokens - MeterNo: {}, NumTokens: {}", meterNo, numTokens);
        
        HttpHeaders headers = buildHeadersWithToken();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );
            
            String responseBodyString = rawResponse.getBody();
            int httpStatusCode = rawResponse.getStatusCode().value();
            
            logger.info("Electricity tokens response - Status: {}, Body: {}", httpStatusCode, responseBodyString);
            
            if (httpStatusCode == 200) {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = objectMapper.readTree(responseBodyString);
                    if (jsonNode.has("data")) {
                        // Extract from "data" wrapper
                        JsonNode dataNode = jsonNode.get("data");
                        ElectricityTokenResponse response = new ElectricityTokenResponse();
                        response.setData(objectMapper.treeToValue(dataNode, 
                            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, ElectricityTokenResponse.TokenData.class)));
                        logger.info("Electricity tokens retrieved successfully - Count: {}", 
                            response.getData() != null ? response.getData().size() : 0);
                        return response;
                    } else {
                        // Try direct parsing
                        return objectMapper.readValue(responseBodyString, ElectricityTokenResponse.class);
                    }
                } catch (Exception e) {
                    logger.error("Error parsing electricity tokens response: ", e);
                    throw new RuntimeException("Failed to parse electricity tokens response: " + e.getMessage());
                }
            } else {
                throw new RuntimeException("Failed to get electricity tokens (Status: " + httpStatusCode + "): " + responseBodyString);
            }
        } catch (HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            logger.error("HTTP error getting electricity tokens - Status: {}, Response: {}", statusCode, responseBody);
            
            // If 401, token might be expired - try to refresh
            if (statusCode == 401) {
                logger.warn("Authentication failed, clearing token cache and retrying...");
                cachedAccessToken = null;
                tokenExpiresAt = null;
                
                // Retry once with new token
                try {
                    headers = buildHeadersWithToken();
                    entity = new HttpEntity<>(headers);
                    ResponseEntity<String> retryResponse = restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            entity,
                            String.class
                    );
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode jsonNode = objectMapper.readTree(retryResponse.getBody());
                    if (jsonNode.has("data")) {
                        ElectricityTokenResponse response = new ElectricityTokenResponse();
                        response.setData(objectMapper.treeToValue(jsonNode.get("data"), 
                            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, ElectricityTokenResponse.TokenData.class)));
                        return response;
                    } else {
                        return objectMapper.readValue(retryResponse.getBody(), ElectricityTokenResponse.class);
                    }
                } catch (Exception retryException) {
                    logger.error("Electricity tokens retry failed: ", retryException);
                    throw new RuntimeException("Failed to get electricity tokens after retry: " + retryException.getMessage());
                }
            }
            
            throw new RuntimeException("Failed to get electricity tokens (Status: " + statusCode + "): " + responseBody);
        } catch (Exception e) {
            logger.error("Error getting electricity tokens: ", e);
            throw new RuntimeException("Failed to get electricity tokens: " + e.getMessage());
        }
    }
    
    /**
     * Get all available verticals from EFASHE API
     * GET /verticals
     * Uses token authentication
     */
    public Object getVerticals() {
        String url = efasheApiUrl + "/verticals";
        
        logger.info("Getting EFASHE verticals list - will authenticate first");
        
        // This will call getAccessToken() which authenticates with /auth endpoint first
        HttpHeaders headers = buildHeadersWithToken();
        logger.info("EFASHE authentication completed, now calling /verticals endpoint");
        
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );
            
            String responseBodyString = rawResponse.getBody();
            int httpStatusCode = rawResponse.getStatusCode().value();
            
            logger.info("EFASHE verticals response - Status: {}, Body: {}", httpStatusCode, responseBodyString);
            
            if (httpStatusCode == 200) {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = objectMapper.readTree(responseBodyString);
                    // Return the parsed JSON (could be wrapped in "data" or direct)
                    if (jsonNode.has("data")) {
                        return objectMapper.treeToValue(jsonNode.get("data"), Object.class);
                    } else {
                        return objectMapper.treeToValue(jsonNode, Object.class);
                    }
                } catch (Exception e) {
                    logger.error("Error parsing verticals response: ", e);
                    // Return raw response if parsing fails
                    return responseBodyString;
                }
            } else {
                throw new RuntimeException("Failed to get verticals (Status: " + httpStatusCode + "): " + responseBodyString);
            }
        } catch (HttpClientErrorException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            logger.error("HTTP error getting verticals - Status: {}, Response: {}", statusCode, responseBody);
            
            // If 401, token might be expired - try to refresh
            if (statusCode == 401) {
                logger.warn("Authentication failed, clearing token cache and retrying...");
                cachedAccessToken = null;
                tokenExpiresAt = null;
                
                // Retry once with new token
                try {
                    headers = buildHeadersWithToken();
                    entity = new HttpEntity<>(headers);
                    ResponseEntity<String> retryResponse = restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            entity,
                            String.class
                    );
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode jsonNode = objectMapper.readTree(retryResponse.getBody());
                    if (jsonNode.has("data")) {
                        return objectMapper.treeToValue(jsonNode.get("data"), Object.class);
                    } else {
                        return objectMapper.treeToValue(jsonNode, Object.class);
                    }
                } catch (Exception retryException) {
                    logger.error("Verticals retry failed: ", retryException);
                    throw new RuntimeException("Failed to get verticals after retry: " + retryException.getMessage());
                }
            }
            
            throw new RuntimeException("Failed to get verticals (Status: " + statusCode + "): " + responseBody);
        } catch (Exception e) {
            logger.error("Error getting verticals: ", e);
            throw new RuntimeException("Failed to get verticals: " + e.getMessage());
        }
    }
}
