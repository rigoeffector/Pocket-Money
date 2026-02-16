package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.entity.FailedMessage;
import com.pocketmoney.pocketmoney.repository.FailedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MessagingService {

    private static final Logger logger = LoggerFactory.getLogger(MessagingService.class);

    @Value("${sms.api.url:https://swiftqom.io/api/dev}")
    private String smsApiUrl;

    @Value("${sms.api.key:SWQWEkheqFdx31PXeKXbVT9MlTU8jzs7Sgtf3ovpkzxb5dimWLTCx9FLLjnZc4YS}")
    private String smsApiKey;

    // @Value("${sms.sender.id:swiftqom}")
    @Value("${sms.sender.id:BEPAY}")
    private String smsSenderId;

    // Bepay SMS Configuration (formerly BeSoft)
    @Value("${sms.type:besoftsms}")
    private String smsType;

    @Value("${sms.type.password:}")
    private String besoftPassword;

    @Value("${sms.soft.url:http://api.rmlconnect.net:8080/bulksms/bulksms?type=0&dlr=1}")
    private String besoftSmsUrl;

    @Value("${sms.sender:BEPAY}")
    private String besoftSender;

    // BEPAY SMS Configuration (for testing)
    @Value("${sms.bepay.api.key:SWQj29yWXdjcjJMzhO3bkk6DNqxrpq6tJ9ZmB21SLZxGmuNhcPXIjVJSwlSeL4uD}")
    private String bepayApiKey;

    @Value("${sms.bepay.sender.id:BEPAY}")
    private String bepaySenderId;

    private final RestTemplate restTemplate;
    private final FailedMessageRepository failedMessageRepository;

    public MessagingService(RestTemplate restTemplate, FailedMessageRepository failedMessageRepository) {
        this.restTemplate = restTemplate;
        this.failedMessageRepository = failedMessageRepository;
    }


    /**
     * Send SMS to a single phone number
     * Supports both Swift.com and Bepay SMS based on configuration
     */
    public void sendSms(String message, String phoneNumber) {
        logger.info("=== START sendSms ===");
        logger.info("Input parameters - Phone: {}, Message length: {}", phoneNumber, message != null ? message.length() : 0);
        logger.info("SMS Type configuration: {}", smsType);
        
        if (phoneNumber == null || phoneNumber.trim().isEmpty() || message == null || message.trim().isEmpty()) {
            logger.warn("Invalid SMS parameters - Phone: {}, Message: {} (null or empty)", phoneNumber, message);
            logger.info("=== END sendSms (invalid parameters) ===");
            return;
        }

        // Use Bepay SMS if configured, otherwise use Swift.com
        if ("besoftsms".equalsIgnoreCase(smsType)) {
            logger.info("Routing to Bepay SMS (sms.type={})", smsType);
            sendSmsViaBeSoft(message, phoneNumber);
        } else {
            logger.info("Routing to Swift.com SMS (sms.type={}, default)", smsType);
            sendSmsViaSwift(message, phoneNumber);
        }
        
        logger.info("=== END sendSms ===");
    }

    /**
     * Send SMS and return true if successful, false otherwise
     * Public method for resending failed messages
     */
    public boolean sendSmsWithResult(String message, String phoneNumber) {
        logger.info("=== START sendSmsWithResult ===");
        logger.info("Input parameters - Phone: {}, Message length: {}", phoneNumber, message != null ? message.length() : 0);
        logger.info("SMS Type configuration: {}", smsType);
        
        if (phoneNumber == null || phoneNumber.trim().isEmpty() || message == null || message.trim().isEmpty()) {
            logger.warn("Invalid SMS parameters - Phone: {}, Message: {} (null or empty)", phoneNumber, message);
            logger.info("=== END sendSmsWithResult (invalid parameters) ===");
            return false;
        }

        try {
            // Use Bepay SMS if configured, otherwise use Swift.com
            if ("besoftsms".equalsIgnoreCase(smsType)) {
                logger.info("Routing to Bepay SMS (sms.type={})", smsType);
                return sendSmsViaBeSoftWithResult(message, phoneNumber);
            } else {
                logger.info("Routing to Swift.com SMS (sms.type={}, default)", smsType);
                return sendSmsViaSwiftWithResult(message, phoneNumber);
            }
        } catch (Exception e) {
            logger.error("Error in sendSmsWithResult: {}", e.getMessage(), e);
            return false;
        } finally {
            logger.info("=== END sendSmsWithResult ===");
        }
    }

    /**
     * Send SMS via Swift.com API
     */
    private void sendSmsViaSwift(String message, String phoneNumber) {
        logger.info("=== START sendSmsViaSwift ===");
        logger.info("Swift.com Configuration - API URL: {}, API Key: {}...{}", 
            smsApiUrl, 
            smsApiKey != null && smsApiKey.length() > 10 ? smsApiKey.substring(0, 10) : "null",
            smsApiKey != null && smsApiKey.length() > 10 ? smsApiKey.substring(smsApiKey.length() - 10) : "");
        
        try {
            // Normalize phone number to 12 digits (250XXXXXXXXX format) for SwiftQOM API
            String normalizedPhone = normalizePhoneTo12Digits(phoneNumber);
            logger.info("Swift.com Phone Normalization - Original: {}, Normalized: {}", phoneNumber, normalizedPhone);
            
            // Build request headers
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-KEY", smsApiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            logger.info("Swift.com Request Headers - Content-Type: {}, X-API-KEY: {}...{}", 
                headers.getContentType(),
                smsApiKey != null && smsApiKey.length() > 10 ? smsApiKey.substring(0, 10) : "null",
                smsApiKey != null && smsApiKey.length() > 10 ? smsApiKey.substring(smsApiKey.length() - 10) : "");

            // Build request body
            Map<String, String> body = new HashMap<>();
            body.put("sender_id", smsSenderId);
            body.put("phone", normalizedPhone);
            body.put("message", message.trim());
            
            logger.info("Swift.com Request Body - sender_id: {} (from config: sms.sender.id), phone: {}, message length: {}", 
                body.get("sender_id"), body.get("phone"), body.get("message").length());

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            // Build full URL
            String fullUrl = smsApiUrl + "/api/v1/send_sms";
            logger.info("Swift.com API Call - Method: POST, URL: {}", fullUrl);
            logger.info("Swift.com API Call - Phone (normalized): {}, Message: {}", normalizedPhone, message.trim());

            // Make API call
            logger.info("Calling Swift.com API...");
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    fullUrl,
                    HttpMethod.POST,
                    request,
                    (Class<Map<String, Object>>)(Class<?>)Map.class
            );

            logger.info("Swift.com API Response received - Status Code: {}, Status Text: {}", 
                response.getStatusCode().value(), response.getStatusCode());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                logger.info("✅ Swift.com SMS sent successfully to: {} (normalized: {})", phoneNumber, normalizedPhone);
                logger.info("Swift.com Response Body: {}", response.getBody());
            } else {
                logger.error("❌ Failed to send SMS via Swift.com to: {} (normalized: {})", phoneNumber, normalizedPhone);
                logger.error("Swift.com Response Status: {}", response.getStatusCode());
                if (response.getBody() != null) {
                    logger.error("Swift.com Response Body: {}", response.getBody());
                }
            }

            logger.info("=== END sendSmsViaSwift ===");
        } catch (Exception e) {
            logger.error("=== ERROR in sendSmsViaSwift ===");
            logger.error("Phone: {}, Error: {}", phoneNumber, e.getMessage());
            logger.error("Exception class: {}", e.getClass().getName());
            logger.error("Stack trace: ", e);
            logger.error("=== END ERROR ===");
            // Don't throw exception - SMS failure shouldn't break the main flow
        }
    }

    /**
     * Send SMS via Bepay SMS API (formerly BeSoft)
     * URL format: http://api.rmlconnect.net:8080/bulksms/bulksms?type=0&dlr=1
     * Parameters: username, password, sender, receiver, message
     */
    public void sendSmsViaBeSoft(String message, String phoneNumber) {
        logger.info("=== START sendSmsViaBepay ===");
        logger.info("Bepay SMS Configuration - Base URL: {}, Username (sms.type): {}, Sender: {}", 
            besoftSmsUrl, smsType, besoftSender);
        logger.info("Bepay Password configured: {}", besoftPassword != null && !besoftPassword.isEmpty() ? "YES" : "NO");
        
        try {
            // Bepay SMS API typically uses GET with query parameters
            // Format: username=<user>&password=<pass>&sender=<sender>&receiver=<phone>&message=<msg>
            
            // Use sms.type as username (usually "besoftsms")
            String username = smsType;
            String password = besoftPassword;
            String sender = besoftSender;
            
            logger.info("Bepay Parameters - Username: {}, Sender: {}, Password length: {}", 
                username, sender, password != null ? password.length() : 0);
            
            // Format phone number for Bepay SMS (remove 250 prefix and add 0)
            String formattedPhone = phoneNumber.trim();
            logger.info("Bepay Phone Formatting - Original: {}", formattedPhone);
            
            if (formattedPhone.startsWith("250")) {
                formattedPhone = "0" + formattedPhone.substring(3);
                logger.info("Bepay Phone Formatting - Converted from 250XXXXXXXXX to: {}", formattedPhone);
            } else {
                logger.info("Bepay Phone Formatting - No conversion needed (doesn't start with 250): {}", formattedPhone);
            }
            
            // Build URL with query parameters
            String encodedUsername = java.net.URLEncoder.encode(username, "UTF-8");
            String encodedPassword = java.net.URLEncoder.encode(password, "UTF-8");
            String encodedSender = java.net.URLEncoder.encode(sender, "UTF-8");
            String encodedPhone = java.net.URLEncoder.encode(formattedPhone, "UTF-8");
            String encodedMessage = java.net.URLEncoder.encode(message.trim(), "UTF-8");
            
            String url = String.format("%s&username=%s&password=%s&sender=%s&receiver=%s&message=%s",
                    besoftSmsUrl,
                    encodedUsername,
                    encodedPassword,
                    encodedSender,
                    encodedPhone,
                    encodedMessage);

            logger.info("Bepay API Call - Method: GET");
            logger.info("Bepay API Call - Base URL: {}", besoftSmsUrl);
            logger.info("Bepay API Call - Full URL (without password): {}&username={}&password=***&sender={}&receiver={}&message={}", 
                besoftSmsUrl, encodedUsername, encodedSender, encodedPhone, 
                message.trim().length() > 50 ? message.trim().substring(0, 50) + "..." : message.trim());
            logger.info("Bepay API Call - Phone (original): {}, Phone (formatted): {}", phoneNumber.trim(), formattedPhone);
            logger.info("Bepay API Call - Message length: {}, Message preview: {}", 
                message.trim().length(), message.trim().length() > 100 ? message.trim().substring(0, 100) + "..." : message.trim());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<String> request = new HttpEntity<>(headers);
            
            logger.info("Bepay Request Headers - Content-Type: {}", headers.getContentType());

            // Make API call
            logger.info("Calling Bepay SMS API...");
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    String.class
            );

            logger.info("Bepay API Response received - Status Code: {}, Status Text: {}", 
                response.getStatusCode().value(), response.getStatusCode());

            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("✅ Bepay SMS sent successfully to: {} (formatted: {})", phoneNumber, formattedPhone);
                logger.info("Bepay Response Body: {}", response.getBody());
            } else {
                logger.error("❌ Failed to send SMS via Bepay to: {} (formatted: {})", phoneNumber, formattedPhone);
                logger.error("Bepay Response Status: {}", response.getStatusCode());
                logger.error("Bepay Response Body: {}", response.getBody());
            }

            logger.info("=== END sendSmsViaBepay ===");
        } catch (Exception e) {
            logger.error("=== ERROR in sendSmsViaBepay ===");
            logger.error("Phone: {}, Error: {}", phoneNumber, e.getMessage());
            logger.error("Exception class: {}", e.getClass().getName());
            logger.error("Stack trace: ", e);
            logger.error("=== END ERROR ===");
            // Don't throw exception - SMS failure shouldn't break the main flow
        }
    }

    /**
     * Send bulk SMS to multiple phone numbers
     * For now, sends SMS individually to each phone number
     * (Can be optimized later if Swift.com supports true bulk SMS endpoint)
     */
    public void sendBulkSms(String message, List<String> phoneNumbers) {
        sendBulkSmsWithResults(message, phoneNumbers);
    }

    /**
     * Send bulk SMS to multiple phone numbers and return detailed results
     * Returns a BulkSmsResponse with success/failure counts and per-recipient results
     */
    public com.pocketmoney.pocketmoney.dto.BulkSmsResponse sendBulkSmsWithResults(String message, List<String> phoneNumbers) {
        logger.info("=== START sendBulkSms ===");
        logger.info("Bulk SMS - Message length: {}, Number of recipients: {}", 
            message != null ? message.length() : 0, phoneNumbers != null ? phoneNumbers.size() : 0);
        logger.info("SMS Provider (sms.type): {}", smsType);
        
        com.pocketmoney.pocketmoney.dto.BulkSmsResponse response = new com.pocketmoney.pocketmoney.dto.BulkSmsResponse();
        response.setTotalRecipients(phoneNumbers != null ? phoneNumbers.size() : 0);
        response.setSuccessCount(0);
        response.setFailureCount(0);
        response.setSkippedCount(0);
        response.setResults(new java.util.ArrayList<>());
        
        if (phoneNumbers == null || phoneNumbers.isEmpty()) {
            logger.warn("No phone numbers provided for bulk SMS");
            logger.info("=== END sendBulkSms (no recipients) ===");
            return response;
        }

        logger.info("Starting bulk SMS to {} recipient(s) using provider: {}", phoneNumbers.size(), smsType);

        // Send SMS to each phone number individually
        for (int i = 0; i < phoneNumbers.size(); i++) {
            String phoneNumber = phoneNumbers.get(i);
            com.pocketmoney.pocketmoney.dto.BulkSmsResponse.RecipientResult result = 
                new com.pocketmoney.pocketmoney.dto.BulkSmsResponse.RecipientResult();
            result.setPhoneNumber(phoneNumber);
            
            if (phoneNumber != null && !phoneNumber.trim().isEmpty()) {
                logger.info("Bulk SMS [{}/{}] - Sending to: {}", i + 1, phoneNumbers.size(), phoneNumber);
                try {
                    boolean success = sendSmsWithResult(message, phoneNumber);
                    if (success) {
                        result.setSuccess(true);
                        response.setSuccessCount(response.getSuccessCount() + 1);
                        logger.info("Bulk SMS [{}/{}] - Success: {}", i + 1, phoneNumbers.size(), phoneNumber);
                    } else {
                        result.setSuccess(false);
                        result.setErrorMessage("Failed to send SMS");
                        response.setFailureCount(response.getFailureCount() + 1);
                        logger.error("Bulk SMS [{}/{}] - Failed: {}", i + 1, phoneNumbers.size(), phoneNumber);
                        // Save failed message to database
                        saveFailedMessage("SMS", phoneNumber, message, "Failed to send SMS");
                    }
                } catch (Exception e) {
                    result.setSuccess(false);
                    result.setErrorMessage(e.getMessage());
                    response.setFailureCount(response.getFailureCount() + 1);
                    logger.error("Bulk SMS [{}/{}] - Failed: {}, Error: {}", i + 1, phoneNumbers.size(), phoneNumber, e.getMessage());
                    // Save failed message to database
                    saveFailedMessage("SMS", phoneNumber, message, e.getMessage());
                }
            } else {
                result.setSuccess(false);
                result.setErrorMessage("Phone number is null or empty");
                response.setSkippedCount(response.getSkippedCount() + 1);
                logger.warn("Bulk SMS [{}/{}] - Skipped (null or empty): {}", i + 1, phoneNumbers.size(), phoneNumber);
            }
            
            response.getResults().add(result);
        }

        logger.info("=== END sendBulkSms ===");
        logger.info("Bulk SMS Summary - Total: {}, Success: {}, Failed: {}, Skipped: {}", 
            response.getTotalRecipients(), response.getSuccessCount(), response.getFailureCount(), response.getSkippedCount());
        
        return response;
    }

    /**
     * Send SMS via Swift.com API with BEPAY sender ID and API key
     * Used for testing BEPAY configuration
     */
    public void sendSmsViaBEPAY(String message, String phoneNumber) {
        logger.info("=== START sendSmsViaBEPAY ===");
        logger.info("BEPAY Configuration - API URL: {}, API Key: {}...{}", 
            smsApiUrl, 
            bepayApiKey != null && bepayApiKey.length() > 10 ? bepayApiKey.substring(0, 10) : "null",
            bepayApiKey != null && bepayApiKey.length() > 10 ? bepayApiKey.substring(bepayApiKey.length() - 10) : "");
        logger.info("BEPAY Sender ID: {}", bepaySenderId);
        
        try {
            // Build request headers
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-KEY", bepayApiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            logger.info("BEPAY Request Headers - Content-Type: {}, X-API-KEY: {}...{}", 
                headers.getContentType(),
                bepayApiKey != null && bepayApiKey.length() > 10 ? bepayApiKey.substring(0, 10) : "null",
                bepayApiKey != null && bepayApiKey.length() > 10 ? bepayApiKey.substring(bepayApiKey.length() - 10) : "");

            // Normalize phone number to 12 digits (250XXXXXXXXX format) for SwiftQOM API
            String normalizedPhone = normalizePhoneTo12Digits(phoneNumber);
            logger.info("BEPAY Phone Normalization - Original: {}, Normalized: {}", phoneNumber, normalizedPhone);
            
            // Build request body with BEPAY sender ID
            Map<String, String> body = new HashMap<>();
            body.put("sender_id", bepaySenderId);
            body.put("phone", normalizedPhone);
            body.put("message", message.trim());
            
            logger.info("BEPAY Request Body - sender_id: {}, phone (normalized): {}, message length: {}", 
                body.get("sender_id"), body.get("phone"), body.get("message").length());

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            // Build full URL
            String fullUrl = smsApiUrl + "/api/v1/send_sms";
            logger.info("BEPAY API Call - Method: POST, URL: {}", fullUrl);
            logger.info("BEPAY API Call - Phone (normalized): {}, Message: {}", normalizedPhone, message.trim());

            // Make API call
            logger.info("Calling BEPAY API...");
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    fullUrl,
                    HttpMethod.POST,
                    request,
                    (Class<Map<String, Object>>)(Class<?>)Map.class
            );

            logger.info("BEPAY API Response received - Status Code: {}, Status Text: {}", 
                response.getStatusCode().value(), response.getStatusCode());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                logger.info("✅ BEPAY SMS sent successfully to: {} (normalized: {})", phoneNumber, normalizedPhone);
                logger.info("BEPAY Response Body: {}", response.getBody());
            } else {
                logger.error("❌ Failed to send SMS via BEPAY to: {} (normalized: {})", phoneNumber, normalizedPhone);
                logger.error("BEPAY Response Status: {}", response.getStatusCode());
                if (response.getBody() != null) {
                    logger.error("BEPAY Response Body: {}", response.getBody());
                }
            }

            logger.info("=== END sendSmsViaBEPAY ===");
        } catch (Exception e) {
            logger.error("=== ERROR in sendSmsViaBEPAY ===");
            logger.error("Phone: {}, Error: {}", phoneNumber, e.getMessage());
            logger.error("Exception class: {}", e.getClass().getName());
            logger.error("Stack trace: ", e);
            logger.error("=== END ERROR ===");
            // Don't throw exception - SMS failure shouldn't break the main flow
        }
    }


    /**
     * Send SMS via Swift.com API and return true if successful
     */
    private boolean sendSmsViaSwiftWithResult(String message, String phoneNumber) {
        try {
            // Normalize phone number to 12 digits (250XXXXXXXXX format) for SwiftQOM API
            String normalizedPhone = normalizePhoneTo12Digits(phoneNumber);
            logger.info("Swift.com Phone Normalization - Original: {}, Normalized: {}", phoneNumber, normalizedPhone);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-KEY", smsApiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new HashMap<>();
            body.put("sender_id", smsSenderId);
            body.put("phone", normalizedPhone);
            body.put("message", message.trim());

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            String fullUrl = smsApiUrl + "/api/v1/send_sms";

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    fullUrl,
                    HttpMethod.POST,
                    request,
                    (Class<Map<String, Object>>)(Class<?>)Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                logger.info("✅ Swift.com SMS sent successfully to: {} (normalized: {})", phoneNumber, normalizedPhone);
                return true;
            } else {
                logger.error("❌ Failed to send SMS via Swift.com to: {} (normalized: {})", phoneNumber, normalizedPhone);
                return false;
            }
        } catch (Exception e) {
            logger.error("Exception in sendSmsViaSwiftWithResult: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Send SMS via Bepay SMS API and return true if successful
     */
    private boolean sendSmsViaBeSoftWithResult(String message, String phoneNumber) {
        try {
            String username = smsType;
            String password = besoftPassword;
            String sender = besoftSender;
            
            String formattedPhone = phoneNumber.trim();
            if (formattedPhone.startsWith("250")) {
                formattedPhone = "0" + formattedPhone.substring(3);
            }
            
            String encodedUsername = java.net.URLEncoder.encode(username, "UTF-8");
            String encodedPassword = java.net.URLEncoder.encode(password, "UTF-8");
            String encodedSender = java.net.URLEncoder.encode(sender, "UTF-8");
            String encodedPhone = java.net.URLEncoder.encode(formattedPhone, "UTF-8");
            String encodedMessage = java.net.URLEncoder.encode(message.trim(), "UTF-8");
            
            String url = String.format("%s&username=%s&password=%s&sender=%s&receiver=%s&message=%s",
                    besoftSmsUrl,
                    encodedUsername,
                    encodedPassword,
                    encodedSender,
                    encodedPhone,
                    encodedMessage);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<String> request = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("✅ Bepay SMS sent successfully to: {} (formatted: {})", phoneNumber, formattedPhone);
                return true;
            } else {
                logger.error("❌ Failed to send SMS via Bepay to: {} (formatted: {})", phoneNumber, formattedPhone);
                return false;
            }
        } catch (Exception e) {
            logger.error("Exception in sendSmsViaBeSoftWithResult: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Save failed message to database
     */
    @Transactional
    public void saveFailedMessage(String messageType, String phoneNumber, String message, String errorMessage) {
        try {
            FailedMessage failedMessage = new FailedMessage();
            failedMessage.setMessageType(messageType);
            failedMessage.setPhoneNumber(phoneNumber);
            failedMessage.setMessage(message != null && message.length() > 2000 ? message.substring(0, 2000) : message);
            failedMessage.setErrorMessage(errorMessage != null && errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage);
            failedMessage.setStatus("PENDING");
            failedMessage.setRetryCount(0);
            failedMessageRepository.save(failedMessage);
            logger.info("Saved failed {} message to database for phone: {}", messageType, phoneNumber);
        } catch (Exception e) {
            logger.error("Error saving failed message to database: {}", e.getMessage(), e);
        }
    }

    /**
     * Normalize phone number to 12 digits with 250 prefix (required by SwiftQOM API)
     * Format: 250XXXXXXXXX (exactly 12 digits)
     */
    private String normalizePhoneTo12Digits(String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            throw new RuntimeException("Phone number cannot be null or empty");
        }
        
        // Remove all non-digit characters
        String digitsOnly = phone.replaceAll("[^0-9]", "");
        
        // Handle different formats
        if (digitsOnly.startsWith("250") && digitsOnly.length() == 12) {
            // Already in correct format: 250XXXXXXXXX
            return digitsOnly;
        } else if (digitsOnly.startsWith("0") && digitsOnly.length() == 10) {
            // Format: 0XXXXXXXXX -> 250XXXXXXXXX
            return "250" + digitsOnly.substring(1);
        } else if (digitsOnly.length() == 9) {
            // Format: XXXXXXXXX -> 250XXXXXXXXX
            return "250" + digitsOnly;
        } else if (digitsOnly.startsWith("250") && digitsOnly.length() > 12) {
            // If it's longer than 12, take the first 12 digits
            return digitsOnly.substring(0, 12);
        } else if (digitsOnly.length() > 9) {
            // If it's longer than 9, take last 9 digits and add 250
            String last9 = digitsOnly.substring(digitsOnly.length() - 9);
            return "250" + last9;
        } else {
            throw new RuntimeException("Invalid phone number format. Expected 9, 10, or 12 digits. Got: " + phone + " (normalized: " + digitsOnly + ", length: " + digitsOnly.length() + ")");
        }
    }
}

