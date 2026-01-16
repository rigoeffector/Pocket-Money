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
    private String smsApiUrl; // Always use /api/prod, not /api/dev

    @Value("${sms.api.key:SWQRyoqZr2FhTYpll3m7etmKLhgrDTuUEGLDHjWgx5CSV2DFFavXjQODVzBhqjqL}")
    private String smsApiKey;

    private final RestTemplate restTemplate;

    public MessagingService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void sendSms(String message, String phoneNumber) {
        sendSingleSmsSwiftcom(message, phoneNumber);
    }

    public void sendBulkSms(String message, List<String> phoneNumbers) {
        if (phoneNumbers == null || phoneNumbers.isEmpty()) {
            logger.warn("No phone numbers provided for SMS");
            return;
        }

        // Send one SMS per phone number
        for (String phoneNumber : phoneNumbers) {
            sendSingleSmsSwiftcom(message, phoneNumber);
        }
    }

    /**
     * Send single SMS via Swiftcom (SwiftQOM) API
     * Matches the reference implementation exactly (sendSingleSms from SmsService)
     * Reference uses: smsApiUrl + "/api/v1/send_sms" where smsApiUrl = "https://swiftqom.io/api/dev"
     * Result: "https://swiftqom.io/api/dev/api/v1/send_sms" (duplicate /api is intentional)
     */
    private void sendSingleSmsSwiftcom(String message, String recipient) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-KEY", smsApiKey);
            headers.set("Content-Type", "application/json");

            Map<String, String> body = new HashMap<>();
            body.put("sender_id", "swiftqom");
            body.put("phone", recipient);
            body.put("message", message);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            // Build URL exactly as reference: smsApiUrl + "/api/v1/send_sms"
            // Reference uses: "https://swiftqom.io/api/dev" + "/api/v1/send_sms"
            // Result: "https://swiftqom.io/api/dev/api/v1/send_sms" (duplicate /api is intentional)
            // NOTE: Testing shows /api/prod endpoints return 405, but /api/dev works
            // If smsApiUrl contains /api/prod, we'll use /api/dev instead for now
            String url;
            if (smsApiUrl.contains("/api/prod")) {
                // /api/prod doesn't support the same endpoint structure
                // Use /api/dev which is known to work (as per reference implementation)
                String devUrl = smsApiUrl.replace("/api/prod", "/api/dev");
                url = devUrl + "/api/v1/send_sms";
                logger.warn("Using /api/dev endpoint instead of /api/prod (prod endpoints return 405): {}", url);
            } else {
                // Use reference pattern for /api/dev
                url = smsApiUrl + "/api/v1/send_sms";
            }
            
            logger.info("Sending SMS to: {} via Swiftcom", recipient);
            logger.info("SMS API Base URL: {}", smsApiUrl);
            logger.info("Full SMS API URL: {}", url);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    (Class<Map<String, Object>>)(Class<?>)Map.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("SMS sent successfully to: {}", recipient);
            } else {
                logger.error("Failed to send SMS to {}: {}", recipient, response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Error sending SMS to {}: {}", recipient, e.getMessage());
        }
    }

}

