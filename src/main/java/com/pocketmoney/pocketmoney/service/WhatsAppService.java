package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.WhatsAppRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class WhatsAppService {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppService.class);

    @Value("${whatsapp.api.url:https://server.yunotify.com/api}")
    private String whatsappApiUrl;

    private final RestTemplate restTemplate;

    public WhatsAppService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void sendWhatsApp(String message, String phoneNumber) {
        sendBulkWhatsApp(message, java.util.List.of(phoneNumber));
    }

    public void sendBulkWhatsApp(String message, List<String> phoneNumbers) {
        if (phoneNumbers == null || phoneNumbers.isEmpty()) {
            logger.warn("No phone numbers provided for WhatsApp");
            return;
        }

        String url = whatsappApiUrl + "/whatsapp/bulk-json";
        
        WhatsAppRequest request = new WhatsAppRequest();
        request.setMessage(message);
        request.setPhoneNumbers(phoneNumbers);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<WhatsAppRequest> entity = new HttpEntity<>(request, headers);

        try {
            logger.info("Sending WhatsApp message to {} recipient(s)", phoneNumbers.size());
            logger.debug("WhatsApp URL: {}, Message length: {}", url, message.length());
            
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            
            logger.info("WhatsApp message sent successfully. Response status: {}", response.getStatusCode());
            logger.debug("WhatsApp response body: {}", response.getBody());
        } catch (Exception e) {
            logger.error("Error sending WhatsApp message: ", e);
            // Don't throw exception - WhatsApp failure shouldn't break the main flow
        }
    }
}

