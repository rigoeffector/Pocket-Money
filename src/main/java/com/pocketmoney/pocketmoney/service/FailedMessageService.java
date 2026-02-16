package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.FailedMessageResponse;
import com.pocketmoney.pocketmoney.dto.PaginatedResponse;
import com.pocketmoney.pocketmoney.dto.ResendMessageResponse;
import com.pocketmoney.pocketmoney.entity.FailedMessage;
import com.pocketmoney.pocketmoney.repository.FailedMessageRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FailedMessageService {

    private static final Logger logger = LoggerFactory.getLogger(FailedMessageService.class);

    private final FailedMessageRepository failedMessageRepository;
    private final MessagingService messagingService;
    private final WhatsAppService whatsAppService;
    private final EntityManager entityManager;

    public FailedMessageService(
            FailedMessageRepository failedMessageRepository,
            MessagingService messagingService,
            WhatsAppService whatsAppService,
            EntityManager entityManager) {
        this.failedMessageRepository = failedMessageRepository;
        this.messagingService = messagingService;
        this.whatsAppService = whatsAppService;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PaginatedResponse<FailedMessageResponse> getFailedMessages(
            int page, int size, String messageType, String status, String search) {
        
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT fm FROM FailedMessage fm WHERE 1=1 ");

        if (messageType != null && !messageType.trim().isEmpty()) {
            queryBuilder.append("AND UPPER(fm.messageType) = UPPER(:messageType) ");
        }

        if (status != null && !status.trim().isEmpty()) {
            queryBuilder.append("AND UPPER(fm.status) = UPPER(:status) ");
        }

        if (search != null && !search.trim().isEmpty()) {
            queryBuilder.append("AND (LOWER(fm.phoneNumber) LIKE LOWER(:search) OR ");
            queryBuilder.append("LOWER(fm.message) LIKE LOWER(:search) OR ");
            queryBuilder.append("LOWER(COALESCE(fm.errorMessage, '')) LIKE LOWER(:search)) ");
        }

        queryBuilder.append("ORDER BY fm.createdAt DESC");

        Query query = entityManager.createQuery(queryBuilder.toString(), FailedMessage.class);

        if (messageType != null && !messageType.trim().isEmpty()) {
            query.setParameter("messageType", messageType.trim().toUpperCase());
        }

        if (status != null && !status.trim().isEmpty()) {
            query.setParameter("status", status.trim().toUpperCase());
        }

        if (search != null && !search.trim().isEmpty()) {
            query.setParameter("search", "%" + search.trim() + "%");
        }

        // Get total count
        StringBuilder countQueryBuilder = new StringBuilder();
        countQueryBuilder.append("SELECT COUNT(fm) FROM FailedMessage fm WHERE 1=1 ");

        if (messageType != null && !messageType.trim().isEmpty()) {
            countQueryBuilder.append("AND UPPER(fm.messageType) = UPPER(:messageType) ");
        }

        if (status != null && !status.trim().isEmpty()) {
            countQueryBuilder.append("AND UPPER(fm.status) = UPPER(:status) ");
        }

        if (search != null && !search.trim().isEmpty()) {
            countQueryBuilder.append("AND (LOWER(fm.phoneNumber) LIKE LOWER(:search) OR ");
            countQueryBuilder.append("LOWER(fm.message) LIKE LOWER(:search) OR ");
            countQueryBuilder.append("LOWER(COALESCE(fm.errorMessage, '')) LIKE LOWER(:search)) ");
        }

        Query countQuery = entityManager.createQuery(countQueryBuilder.toString(), Long.class);

        if (messageType != null && !messageType.trim().isEmpty()) {
            countQuery.setParameter("messageType", messageType.trim().toUpperCase());
        }

        if (status != null && !status.trim().isEmpty()) {
            countQuery.setParameter("status", status.trim().toUpperCase());
        }

        if (search != null && !search.trim().isEmpty()) {
            countQuery.setParameter("search", "%" + search.trim() + "%");
        }

        long totalElements = (Long) countQuery.getSingleResult();

        // Apply pagination
        int offset = page * size;
        query.setFirstResult(offset);
        query.setMaxResults(size);

        @SuppressWarnings("unchecked")
        List<FailedMessage> failedMessages = (List<FailedMessage>) query.getResultList();

        List<FailedMessageResponse> content = failedMessages.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        int totalPages = (int) Math.ceil((double) totalElements / size);

        PaginatedResponse<FailedMessageResponse> response = new PaginatedResponse<>();
        response.setContent(content);
        response.setTotalElements(totalElements);
        response.setTotalPages(totalPages);
        response.setCurrentPage(page);
        response.setPageSize(size);
        response.setFirst(page == 0);
        response.setLast(page >= totalPages - 1);

        return response;
    }

    @Transactional
    public ResendMessageResponse resendFailedMessages(List<UUID> failedMessageIds) {
        ResendMessageResponse response = new ResendMessageResponse();
        response.setTotalRequested(failedMessageIds.size());
        response.setSuccessCount(0);
        response.setFailureCount(0);
        response.setResults(new java.util.ArrayList<>());

        for (UUID failedMessageId : failedMessageIds) {
            ResendMessageResponse.ResendResult result = new ResendMessageResponse.ResendResult();
            result.setFailedMessageId(failedMessageId);

            try {
                FailedMessage failedMessage = failedMessageRepository.findById(failedMessageId)
                        .orElseThrow(() -> new RuntimeException("Failed message not found: " + failedMessageId));

                result.setPhoneNumber(failedMessage.getPhoneNumber());

                // Update status to RESENT
                failedMessage.setStatus("RESENT");
                failedMessage.setLastRetryAt(LocalDateTime.now());
                failedMessage.setRetryCount(failedMessage.getRetryCount() + 1);
                failedMessageRepository.save(failedMessage);

                // Attempt to resend
                boolean success = false;
                String errorMessage = null;

                try {
                    if ("SMS".equalsIgnoreCase(failedMessage.getMessageType())) {
                        success = messagingService.sendSmsWithResult(failedMessage.getMessage(), failedMessage.getPhoneNumber());
                    } else if ("WHATSAPP".equalsIgnoreCase(failedMessage.getMessageType())) {
                        success = whatsAppService.sendWhatsAppWithResult(failedMessage.getMessage(), failedMessage.getPhoneNumber());
                    } else {
                        errorMessage = "Unknown message type: " + failedMessage.getMessageType();
                    }
                } catch (Exception e) {
                    errorMessage = e.getMessage();
                    logger.error("Error resending message {}: {}", failedMessageId, e.getMessage(), e);
                }

                if (success) {
                    failedMessage.setStatus("RESENT_SUCCESS");
                    result.setSuccess(true);
                    response.setSuccessCount(response.getSuccessCount() + 1);
                } else {
                    failedMessage.setStatus("RESENT_FAILED");
                    failedMessage.setErrorMessage(errorMessage);
                    result.setSuccess(false);
                    result.setErrorMessage(errorMessage);
                    response.setFailureCount(response.getFailureCount() + 1);
                }

                failedMessageRepository.save(failedMessage);

            } catch (Exception e) {
                result.setSuccess(false);
                result.setErrorMessage(e.getMessage());
                response.setFailureCount(response.getFailureCount() + 1);
                logger.error("Error processing failed message {}: {}", failedMessageId, e.getMessage(), e);
            }

            response.getResults().add(result);
        }

        return response;
    }

    private FailedMessageResponse mapToResponse(FailedMessage failedMessage) {
        FailedMessageResponse response = new FailedMessageResponse();
        response.setId(failedMessage.getId());
        response.setMessageType(failedMessage.getMessageType());
        response.setPhoneNumber(failedMessage.getPhoneNumber());
        response.setMessage(failedMessage.getMessage());
        response.setErrorMessage(failedMessage.getErrorMessage());
        response.setRetryCount(failedMessage.getRetryCount());
        response.setStatus(failedMessage.getStatus());
        response.setLastRetryAt(failedMessage.getLastRetryAt());
        response.setCreatedAt(failedMessage.getCreatedAt());
        response.setUpdatedAt(failedMessage.getUpdatedAt());
        return response;
    }
}
