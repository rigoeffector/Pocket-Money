package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.SmsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class MessagingService {

    private static final Logger logger = LoggerFactory.getLogger(MessagingService.class);

    @Value("${sms.api.url:https://swiftqom.io/api/prod}")
    private String smsApiUrl;

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

        try {
            // SwiftQom API uses POST request with JSON body
            String url = smsApiUrl + "/sms/bulk-json";
            
            SmsRequest request = new SmsRequest();
            request.setMessage(message);
            request.setPhoneNumbers(phoneNumbers);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<SmsRequest> entity = new HttpEntity<>(request, headers);

            logger.info("Sending SMS to {} recipient(s) via SwiftQom", phoneNumbers.size());
            logger.debug("SMS URL: {}, Message length: {}", url, message.length());
            
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            
            logger.info("SMS sent successfully. Response status: {}", response.getStatusCode());
            logger.debug("SMS response body: {}", response.getBody());
        } catch (Exception e) {
            logger.error("Error sending SMS via SwiftQom: ", e);
            // Don't throw exception - SMS failure shouldn't break the main flow
        }
    }
}

