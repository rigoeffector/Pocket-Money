package com.pocketmoney.pocketmoney.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketmoney.pocketmoney.entity.EfasheTransaction;
import com.pocketmoney.pocketmoney.repository.EfasheTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every 5 minutes, finds EFASHE transactions that have not received a callback
 * (still pending) and were created more than 5 minutes ago, then calls the MoMo
 * bulk transaction status API.
 *
 * We send the <b>transactionId from the process response</b> (POST /api/efashe/process/{id}
 * or /api/efashe/bizao/process/{id}) – i.e. data.transactionId like "CYD1770676018" –
 * not the path id (e.g. EFASHE-CD52...). That value is stored in EfasheTransaction
 * after process and sent as external_transaction_id to the bulk API.
 * For any bulk response entry with status SUCCESS, we update the transaction accordingly.
 */
@Service
public class MomoBulkStatusScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MomoBulkStatusScheduler.class);
    private static final int PENDING_OLDER_THAN_MINUTES = 5;

    @Value("${momo.bulk.status.url:http://41.186.14.66:443/api/v1/momo/transactionstatus/bulk}")
    private String bulkStatusUrl;

    private final EfasheTransactionRepository efasheTransactionRepository;
    private final EfashePaymentService efashePaymentService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MomoBulkStatusScheduler(EfasheTransactionRepository efasheTransactionRepository,
                                  EfashePaymentService efashePaymentService,
                                  RestTemplate restTemplate) {
        this.efasheTransactionRepository = efasheTransactionRepository;
        this.efashePaymentService = efashePaymentService;
        this.restTemplate = restTemplate;
    }

    @Scheduled(fixedDelayString = "${momo.bulk.status.interval.ms:300000}") // 5 minutes default
    public void pollPendingTransactionsBulkStatus() {
        try {
            LocalDateTime beforeTime = LocalDateTime.now().minusMinutes(PENDING_OLDER_THAN_MINUTES);
            List<EfasheTransaction> pending = efasheTransactionRepository.findPendingWithExternalIdCreatedBefore(beforeTime);
            if (pending == null || pending.isEmpty()) {
                logger.debug("Momo bulk status: no pending transactions older than {} minutes", PENDING_OLDER_THAN_MINUTES);
                return;
            }

            // Use the transactionId from the process response (data.transactionId), same as we return from POST /api/efashe/process/{id}
            List<String> externalIds = new ArrayList<>();
            for (EfasheTransaction t : pending) {
                String id = t.getTransactionId() != null ? t.getTransactionId() : t.getMopayTransactionId();
                if (id != null && !id.trim().isEmpty()) {
                    externalIds.add(id.trim());
                }
            }
            if (externalIds.isEmpty()) {
                return;
            }

            String commaSeparated = String.join(",", externalIds);
            logger.info("Momo bulk status: checking {} pending transaction(s) older than {} min: {}", pending.size(), PENDING_OLDER_THAN_MINUTES, commaSeparated.length() > 200 ? commaSeparated.substring(0, 200) + "..." : commaSeparated);

            Map<String, String> requestBody = Map.of("external_transaction_id", commaSeparated);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(bulkStatusUrl, entity, String.class);
            String body = response.getBody();
            if (body == null || body.trim().isEmpty()) {
                logger.warn("Momo bulk status: empty response from {}", bulkStatusUrl);
                return;
            }

            List<Map<String, Object>> items = parseBulkResponse(body);
            int updated = 0;
            for (Map<String, Object> item : items) {
                String externalId = getString(item, "external_transaction_id", "externalTransactionId", "transaction_id", "transactionId");
                String status = getString(item, "status", "transaction_status");
                if (externalId == null || externalId.isEmpty()) {
                    continue;
                }
                boolean success = "SUCCESS".equalsIgnoreCase(status) || "SUCCESSFUL".equalsIgnoreCase(status)
                        || "200".equals(status) || "201".equals(status);
                if (success) {
                    try {
                        efashePaymentService.applyCallbackStatusFromBulk(externalId, 200, "SUCCESSFUL");
                        updated++;
                        logger.info("Momo bulk status: updated transaction {} to SUCCESS", externalId);
                    } catch (Exception e) {
                        logger.warn("Momo bulk status: failed to update transaction {}: {}", externalId, e.getMessage());
                    }
                }
            }
            if (updated > 0) {
                logger.info("Momo bulk status: updated {} transaction(s) to SUCCESS", updated);
            }
        } catch (Exception e) {
            logger.error("Momo bulk status poll failed: {}", e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseBulkResponse(String body) {
        try {
            if (body != null && body.trim().startsWith("[")) {
                return objectMapper.readValue(body, new TypeReference<List<Map<String, Object>>>() {});
            }
            Map<String, Object> root = objectMapper.readValue(body, Map.class);
            if (root.containsKey("data") && root.get("data") instanceof List) {
                return (List<Map<String, Object>>) root.get("data");
            }
            if (root.containsKey("transactions") && root.get("transactions") instanceof List) {
                return (List<Map<String, Object>>) root.get("transactions");
            }
            if (root.containsKey("results") && root.get("results") instanceof List) {
                return (List<Map<String, Object>>) root.get("results");
            }
            // Single object with external_transaction_id and status
            if (root.containsKey("external_transaction_id") || root.containsKey("status")) {
                List<Map<String, Object>> one = new ArrayList<>();
                one.add(root);
                return one;
            }
        } catch (Exception e) {
            logger.warn("Momo bulk status: could not parse response: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    private String getString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object v = map.get(key);
            if (v != null) {
                return v.toString().trim();
            }
        }
        return null;
    }
}
