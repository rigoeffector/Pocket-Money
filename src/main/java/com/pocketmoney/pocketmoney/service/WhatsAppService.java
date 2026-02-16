package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.WhatsAppRequest;
import com.pocketmoney.pocketmoney.entity.FailedMessage;
import com.pocketmoney.pocketmoney.repository.FailedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class WhatsAppService {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppService.class);

    @Value("${whatsapp.api.url:https://server.yunotify.com/api}")
    private String whatsappApiUrl;

    private final RestTemplate restTemplate;
    private final FailedMessageRepository failedMessageRepository;

    public WhatsAppService(RestTemplate restTemplate, FailedMessageRepository failedMessageRepository) {
        this.restTemplate = restTemplate;
        this.failedMessageRepository = failedMessageRepository;
    }

    public void sendWhatsApp(String message, String phoneNumber) {
        sendBulkWhatsApp(message, java.util.List.of(phoneNumber));
    }

    /**
     * Send WhatsApp and return true if successful, false otherwise
     * Public method for resending failed messages
     */
    public boolean sendWhatsAppWithResult(String message, String phoneNumber) {
        try {
            String url = whatsappApiUrl + "/sms/bulk-json";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            WhatsAppRequest request = new WhatsAppRequest();
            request.setMessage(message);
            request.setPhoneNumbers(java.util.List.of(phoneNumber));

            HttpEntity<WhatsAppRequest> entity = new HttpEntity<>(request, headers);
            
            ResponseEntity<String> apiResponse = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            
            if (apiResponse.getStatusCode().is2xxSuccessful()) {
                logger.info("✅ WhatsApp sent successfully to: {}", phoneNumber);
                return true;
            } else {
                logger.error("❌ Failed to send WhatsApp to: {}", phoneNumber);
                return false;
            }
        } catch (Exception e) {
            logger.error("Exception in sendWhatsAppWithResult: {}", e.getMessage(), e);
            return false;
        }
    }

    public void sendBulkWhatsApp(String message, List<String> phoneNumbers) {
        sendBulkWhatsAppWithResults(message, phoneNumbers);
    }

    /**
     * Send bulk WhatsApp to multiple phone numbers and return detailed results
     * Returns a BulkWhatsAppResponse with success/failure counts and per-recipient results
     */
    public com.pocketmoney.pocketmoney.dto.BulkWhatsAppResponse sendBulkWhatsAppWithResults(String message, List<String> phoneNumbers) {
        logger.info("=== START sendBulkWhatsApp ===");
        logger.info("Bulk WhatsApp - Message length: {}, Number of recipients: {}", 
            message != null ? message.length() : 0, phoneNumbers != null ? phoneNumbers.size() : 0);
        
        com.pocketmoney.pocketmoney.dto.BulkWhatsAppResponse response = new com.pocketmoney.pocketmoney.dto.BulkWhatsAppResponse();
        response.setTotalRecipients(phoneNumbers != null ? phoneNumbers.size() : 0);
        response.setSuccessCount(0);
        response.setFailureCount(0);
        response.setSkippedCount(0);
        response.setResults(new java.util.ArrayList<>());
        
        if (phoneNumbers == null || phoneNumbers.isEmpty()) {
            logger.warn("No phone numbers provided for WhatsApp");
            logger.info("=== END sendBulkWhatsApp (no recipients) ===");
            return response;
        }

        String url = whatsappApiUrl + "/sms/bulk-json";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        logger.info("Starting bulk WhatsApp to {} recipient(s)", phoneNumbers.size());

        // Send WhatsApp to each phone number individually
        for (int i = 0; i < phoneNumbers.size(); i++) {
            String phoneNumber = phoneNumbers.get(i);
            com.pocketmoney.pocketmoney.dto.BulkWhatsAppResponse.RecipientResult result = 
                new com.pocketmoney.pocketmoney.dto.BulkWhatsAppResponse.RecipientResult();
            result.setPhoneNumber(phoneNumber);
            
            if (phoneNumber != null && !phoneNumber.trim().isEmpty()) {
                logger.info("Bulk WhatsApp [{}/{}] - Sending to: {}", i + 1, phoneNumbers.size(), phoneNumber);
                try {
                    WhatsAppRequest request = new WhatsAppRequest();
                    request.setMessage(message);
                    request.setPhoneNumbers(java.util.List.of(phoneNumber));

                    HttpEntity<WhatsAppRequest> entity = new HttpEntity<>(request, headers);
                    
                    ResponseEntity<String> apiResponse = restTemplate.exchange(
                            url,
                            HttpMethod.POST,
                            entity,
                            String.class
                    );
                    
                    result.setSuccess(true);
                    response.setSuccessCount(response.getSuccessCount() + 1);
                    logger.info("Bulk WhatsApp [{}/{}] - Success: {}, Status: {}", 
                        i + 1, phoneNumbers.size(), phoneNumber, apiResponse.getStatusCode());
                } catch (Exception e) {
                    result.setSuccess(false);
                    result.setErrorMessage(e.getMessage());
                    response.setFailureCount(response.getFailureCount() + 1);
                    logger.error("Bulk WhatsApp [{}/{}] - Failed: {}, Error: {}", 
                        i + 1, phoneNumbers.size(), phoneNumber, e.getMessage());
                    // Save failed message to database
                    saveFailedMessage("WHATSAPP", phoneNumber, message, e.getMessage());
                }
            } else {
                result.setSuccess(false);
                result.setErrorMessage("Phone number is null or empty");
                response.setSkippedCount(response.getSkippedCount() + 1);
                logger.warn("Bulk WhatsApp [{}/{}] - Skipped (null or empty): {}", 
                    i + 1, phoneNumbers.size(), phoneNumber);
            }
            
            response.getResults().add(result);
        }

        logger.info("=== END sendBulkWhatsApp ===");
        logger.info("Bulk WhatsApp Summary - Total: {}, Success: {}, Failed: {}, Skipped: {}", 
            response.getTotalRecipients(), response.getSuccessCount(), response.getFailureCount(), response.getSkippedCount());
        
        return response;
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
}

