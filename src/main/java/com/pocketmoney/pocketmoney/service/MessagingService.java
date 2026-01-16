package com.pocketmoney.pocketmoney.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MessagingService {

    private static final Logger logger = LoggerFactory.getLogger(MessagingService.class);

    @Value("${sms.api.url:https://swiftqom.io/api/prod}")
    private String smsApiUrl;

    @Value("${sms.api.key:}")
    private String smsApiKey;

    @Value("${sms.type:swiftcom}")
    private String smsType; // Default to swiftcom

    @Value("${sms.type.password:}")
    private String smsPassword;

    @Value("${sms.soft.url:}")
    private String smsSoftUrl;

    @Value("${sms.sender:Bepay}")
    private String smsSender;

    private final RestTemplate restTemplate;

    public MessagingService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void sendSms(String message, String phoneNumber) {
        sendBulkSms(message, java.util.List.of(phoneNumber));
    }

    public void sendBulkSms(String message, List<String> phoneNumbers) {
        if (phoneNumbers == null || phoneNumbers.isEmpty()) {
            logger.warn("No phone numbers provided for SMS");
            return;
        }

        // Check SMS type and use appropriate service
        if ("besoftsms".equalsIgnoreCase(smsType)) {
            sendBulkSmsBeSoft(message, phoneNumbers);
        } else {
            sendBulkSmsSwiftcom(message, phoneNumbers);
        }
    }

    /**
     * Send SMS via Swiftcom (SwiftQOM) API
     * Uses the correct endpoint: /api/v1/send_sms
     * Request format: { "sender_id": "swiftqom", "phone": "recipient", "message": "message" }
     */
    private void sendBulkSmsSwiftcom(String message, List<String> phoneNumbers) {
        // Send one SMS per phone number (Swiftcom API sends one at a time)
        for (String phoneNumber : phoneNumbers) {
            try {
                // Format phone number - keep original format, just trim (matching reference implementation)
                // The reference code uses recipient.trim() without normalization
                // Swiftcom expects phone in the format it receives (e.g., 250784638201)
                String phone = phoneNumber.trim();
                
                // Build the correct URL (matching reference implementation exactly)
                // Reference uses: smsApiUrl + "/api/v1/send_sms"
                // Where smsApiUrl = "https://swiftqom.io/api/dev" or "https://swiftqom.io/api/prod"
                // Full URL: https://swiftqom.io/api/dev/api/v1/send_sms or https://swiftqom.io/api/prod/api/v1/send_sms
                String url = smsApiUrl + "/api/v1/send_sms";
                
                // Build request body as Map (matching reference implementation)
                Map<String, String> body = new HashMap<>();
                body.put("sender_id", "swiftqom");
                body.put("phone", phone);
                body.put("message", message);
                
                // Set headers (note: X-API-KEY in uppercase, not X-API-Key)
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-API-KEY", smsApiKey != null ? smsApiKey : "");
                headers.setContentType(MediaType.APPLICATION_JSON);
                
                HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
                
                logger.info("Sending SMS via Swiftcom to: {} (original: {})", phone, phoneNumber);
                logger.debug("Swiftcom URL: {}, Message length: {}", url, message.length());
                
                // Send POST request
                @SuppressWarnings("unchecked")
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        url,
                        HttpMethod.POST,
                        request,
                        (Class<Map<String, Object>>)(Class<?>)Map.class
                );
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    logger.info("✅ SMS sent successfully via Swiftcom to {}. Response: {}", phone, response.getBody());
                } else {
                    logger.error("Failed to send SMS to {} via Swiftcom: Status={}", phone, response.getStatusCode());
                }
                
            } catch (Exception e) {
                logger.error("Error sending SMS via Swiftcom to {}: ", phoneNumber, e);
                // Continue with next phone number - don't fail the entire batch
            }
        }
    }

    /**
     * Send SMS via BeSoft SMS API
     */
    private void sendBulkSmsBeSoft(String message, List<String> phoneNumbers) {
        try {
            if (smsSoftUrl == null || smsSoftUrl.trim().isEmpty()) {
                logger.error("BeSoft SMS URL not configured, cannot send SMS");
                return;
            }

            logger.info("Sending SMS to {} recipient(s) via BeSoft SMS API", phoneNumbers.size());
            
            // BeSoft SMS uses GET request with query parameters
            for (String phoneNumber : phoneNumbers) {
                try {
                    // Remove country code prefix if present (250 -> 0)
                    String phone = phoneNumber;
                    if (phone.startsWith("250") && phone.length() == 12) {
                        phone = "0" + phone.substring(3);
                    } else if (phone.startsWith("+250")) {
                        phone = "0" + phone.substring(4);
                    }
                    
                    // Build URL with query parameters
                    String url = smsSoftUrl + 
                        "&username=" + (smsPassword != null ? smsPassword : "") +
                        "&password=" + (smsPassword != null ? smsPassword : "") +
                        "&source=" + (smsSender != null ? smsSender : "Bepay") +
                        "&destination=" + phone +
                        "&message=" + java.net.URLEncoder.encode(message, "UTF-8");

                    logger.debug("BeSoft SMS URL: {}", url.replaceAll("password=[^&]+", "password=***"));

                    ResponseEntity<String> response = restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            null,
                            String.class
                    );
                    
                    logger.info("SMS sent successfully via BeSoft to {}. Response status: {}", phone, response.getStatusCode());
                    logger.debug("BeSoft SMS response body: {}", response.getBody());
                } catch (Exception e) {
                    logger.error("Error sending SMS via BeSoft to {}: ", phoneNumber, e);
                    // Continue with next phone number
                }
            }
        } catch (Exception e) {
            logger.error("Error sending SMS via BeSoft API: ", e);
            // Don't throw exception - SMS failure shouldn't break the main flow
        }
    }
}

