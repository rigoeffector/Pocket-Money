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

    @Value("${sms.api.url:https://swiftqom.io/api/dev}")
    private String smsApiUrl;

    @Value("${sms.api.key:SWQWEkheqFdx31PXeKXbVT9MlTU8jzs7Sgtf3ovpkzxb5dimWLTCx9FLLjnZc4YS}")
    private String smsApiKey;

    private final RestTemplate restTemplate;

    public MessagingService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Send SMS to a single phone number using Swift.com API
     * Uses the same pattern as the example SmsService
     */
    public void sendSms(String message, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty() || message == null || message.trim().isEmpty()) {
            logger.warn("Invalid SMS parameters: phoneNumber={}, message={}", phoneNumber, message);
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-KEY", smsApiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new HashMap<>();
            body.put("sender_id", "swiftqom");
            // body.put("sender_id", "Besoft");
            body.put("phone", phoneNumber.trim());
            body.put("message", message.trim());

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            logger.info("Sending SMS to {} via Swift.com API", phoneNumber);
            logger.debug("SMS URL: {}, Message length: {}", smsApiUrl + "/api/v1/send_sms", message.length());

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    smsApiUrl + "/api/v1/send_sms",
                    HttpMethod.POST,
                    request,
                    (Class<Map<String, Object>>)(Class<?>)Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                logger.info("SMS sent successfully via Swift.com to {}. Response: {}", phoneNumber, response.getBody());
            } else {
                logger.error("Failed to send SMS via Swift.com to {}: Status={}", phoneNumber, response.getStatusCode());
            }

        } catch (Exception e) {
            logger.error("Error sending SMS via Swift.com API to {}: {}", phoneNumber, e.getMessage(), e);
            // Don't throw exception - SMS failure shouldn't break the main flow
        }
    }

    /**
     * Send bulk SMS to multiple phone numbers
     * For now, sends SMS individually to each phone number
     * (Can be optimized later if Swift.com supports true bulk SMS endpoint)
     */
    public void sendBulkSms(String message, List<String> phoneNumbers) {
        if (phoneNumbers == null || phoneNumbers.isEmpty()) {
            logger.warn("No phone numbers provided for SMS");
            return;
        }

        logger.info("Sending bulk SMS to {} recipient(s) via Swift.com API", phoneNumbers.size());

        // Send SMS to each phone number individually
        for (String phoneNumber : phoneNumbers) {
            if (phoneNumber != null && !phoneNumber.trim().isEmpty()) {
                sendSms(message, phoneNumber);
            }
        }

        logger.info("Completed bulk SMS sending to {} recipient(s)", phoneNumbers.size());
    }
}

