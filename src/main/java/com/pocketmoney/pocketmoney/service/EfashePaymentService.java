package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.*;
import com.pocketmoney.pocketmoney.entity.EfasheTransaction;
import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import com.pocketmoney.pocketmoney.entity.User;
import com.pocketmoney.pocketmoney.repository.EfasheTransactionRepository;
import com.pocketmoney.pocketmoney.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class EfashePaymentService {

    private static final Logger logger = LoggerFactory.getLogger(EfashePaymentService.class);

    private final EfasheSettingsService efasheSettingsService;
    private final MoPayService moPayService;
    private final EfasheApiService efasheApiService;
    private final EfasheTransactionRepository efasheTransactionRepository;
    private final WhatsAppService whatsAppService;
    private final MessagingService messagingService;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    public EfashePaymentService(EfasheSettingsService efasheSettingsService, 
                                 MoPayService moPayService,
                                 EfasheApiService efasheApiService,
                                 EfasheTransactionRepository efasheTransactionRepository,
                                 WhatsAppService whatsAppService,
                                 MessagingService messagingService,
                                 UserRepository userRepository,
                                 EntityManager entityManager) {
        this.efasheSettingsService = efasheSettingsService;
        this.moPayService = moPayService;
        this.efasheApiService = efasheApiService;
        this.efasheTransactionRepository = efasheTransactionRepository;
        this.whatsAppService = whatsAppService;
        this.messagingService = messagingService;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
    }

    /**
     * Validate customerAccountNumber based on service type
     * - AIRTIME/MTN: Optional (will use phone if not provided)
     * - TV: Required (Decoder number)
     * - RRA: Required (TIN number)
     * - ELECTRICITY: Required (Cashpower number)
     */
    private void validateCustomerAccountNumber(EfasheInitiateRequest request) {
        EfasheServiceType serviceType = request.getServiceType();
        String customerAccountNumber = request.getCustomerAccountNumber();
        
        if (serviceType == EfasheServiceType.TV || 
            serviceType == EfasheServiceType.RRA || 
            serviceType == EfasheServiceType.ELECTRICITY) {
            
            if (customerAccountNumber == null || customerAccountNumber.trim().isEmpty()) {
                String accountType = serviceType == EfasheServiceType.TV ? "Decoder number" :
                                   serviceType == EfasheServiceType.RRA ? "TIN number" :
                                   "Cashpower number";
                throw new RuntimeException(accountType + " (customerAccountNumber) is required for service type: " + serviceType);
            }
            
            logger.info("Validated customerAccountNumber for {}: {}", serviceType, customerAccountNumber);
        } else if (serviceType == EfasheServiceType.AIRTIME || serviceType == EfasheServiceType.MTN) {
            // Optional for AIRTIME/MTN - will use phone if not provided
            if (customerAccountNumber != null && !customerAccountNumber.trim().isEmpty()) {
                logger.info("Using provided customerAccountNumber for {}: {}", serviceType, customerAccountNumber);
            } else {
                logger.info("customerAccountNumber not provided for {}, will derive from phone", serviceType);
            }
        }
    }

    public EfasheInitiateResponse initiatePayment(EfasheInitiateRequest request) {
        logger.info("Initiating EFASHE payment - Service: {}, Amount: {}, Phone: {}, CustomerAccountNumber: {}", 
            request.getServiceType(), request.getAmount(), request.getPhone(), request.getCustomerAccountNumber());

        // Validate customerAccountNumber based on service type
        validateCustomerAccountNumber(request);

        // Generate transaction ID dynamically starting with "EFASHE"
        String transactionId = generateEfasheTransactionId();
        logger.info("Generated EFASHE transaction ID: {}", transactionId);

        // Get EFASHE settings for the service type
        EfasheSettingsResponse settingsResponse = efasheSettingsService.getSettingsByServiceType(request.getServiceType());

        BigDecimal amount = request.getAmount();
        BigDecimal agentCommissionPercent = settingsResponse.getAgentCommissionPercentage();
        BigDecimal customerCashbackPercent = settingsResponse.getCustomerCashbackPercentage();
        BigDecimal besoftSharePercent = settingsResponse.getBesoftSharePercentage();

        // Calculate amounts based on percentages and round to whole numbers (MoPay requirement)
        BigDecimal customerCashbackAmount = amount.multiply(customerCashbackPercent)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            .setScale(0, RoundingMode.HALF_UP); // Round to whole number
        BigDecimal agentCommissionAmount = amount.multiply(agentCommissionPercent)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            .setScale(0, RoundingMode.HALF_UP); // Round to whole number
        BigDecimal besoftShareAmount = amount.multiply(besoftSharePercent)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            .setScale(0, RoundingMode.HALF_UP); // Round to whole number

        // Full amount phone receives: amount - (customerCashback + besoftShare)
        // Customer gets cashback back and Besoft phone gets their share
        // Agent Commission is included in the full amount phone's portion
        BigDecimal fullAmountPhoneReceives = amount.subtract(customerCashbackAmount)
            .subtract(besoftShareAmount)
            .setScale(0, RoundingMode.HALF_UP); // Round to whole number

        logger.info("Amount breakdown - Total: {}, Customer Cashback: {}, Besoft Share: {}, Agent Commission: {}, Full Amount Phone (remaining after cashback and besoft): {}",
            amount, customerCashbackAmount, besoftShareAmount, agentCommissionAmount, fullAmountPhoneReceives);

        // Validate that fullAmountPhoneReceives is not negative
        if (fullAmountPhoneReceives.compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("Invalid percentage configuration: Total percentages exceed 100%. Full amount phone would receive negative amount: " + fullAmountPhoneReceives);
        }

        // Build MoPay request - NO TRANSFERS in initial request
        // ALL transfers (full amount + customer cashback + besoft share) will be sent ONLY after EFASHE status is SUCCESS
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        moPayRequest.setTransaction_id(transactionId); // Main transaction ID - will be used for ALL transfers later
        logger.info("Setting main transaction ID: {} - NO transfers in initial request, all transfers will be sent after EFASHE SUCCESS", transactionId);
        moPayRequest.setAmount(amount);
        moPayRequest.setCurrency(request.getCurrency());
        
        // Normalize and set customer phone (debit)
        // Phone is now String in request, convert to normalized format
        String normalizedCustomerPhone = normalizePhoneTo12Digits(request.getPhone());
        // MoPay API requires phone as Long, so convert for API call but keep as String in DTO
        moPayRequest.setPhone(normalizedCustomerPhone); // Will be converted to Long in MoPayService if needed
        moPayRequest.setPayment_mode(request.getPayment_mode());
        moPayRequest.setMessage(request.getMessage() != null ? request.getMessage() : 
            "EFASHE " + request.getServiceType() + " payment");
        moPayRequest.setCallback_url(request.getCallback_url());

        // NO TRANSFERS in initial request - all transfers will be sent ONLY after EFASHE status is SUCCESS
        // This includes: full amount transfer, customer cashback, and besoft share
        logger.info("NO transfers in initial MoPay request - ALL transfers (full amount + customer cashback + besoft share) will be sent ONLY after EFASHE status is SUCCESS");

        // Initiate payment with MoPay
        MoPayResponse moPayResponse = moPayService.initiatePayment(moPayRequest);

        // Store transaction record in database for later status checking
        EfasheTransaction transaction = new EfasheTransaction();
        transaction.setTransactionId(transactionId);
        transaction.setServiceType(request.getServiceType());
        transaction.setCustomerPhone(normalizedCustomerPhone);
        
        // Determine customer account number based on service type
        String customerAccountNumber;
        if (request.getCustomerAccountNumber() != null && !request.getCustomerAccountNumber().trim().isEmpty()) {
            // Use provided customerAccountNumber (for TV, RRA, ELECTRICITY)
            customerAccountNumber = request.getCustomerAccountNumber().trim();
            logger.info("Using provided customer account number: {} for service type: {}", customerAccountNumber, request.getServiceType());
        } else {
            // For AIRTIME/MTN, derive from phone number
            // Remove 250 prefix and add 0 prefix: 250784638201 -> 0784638201
            if (request.getServiceType() == EfasheServiceType.AIRTIME || request.getServiceType() == EfasheServiceType.MTN) {
                customerAccountNumber = normalizedCustomerPhone.startsWith("250") 
                    ? "0" + normalizedCustomerPhone.substring(3) 
                    : (normalizedCustomerPhone.startsWith("0") ? normalizedCustomerPhone : "0" + normalizedCustomerPhone);
                logger.info("Derived customer account number from phone: {} -> {} for service type: {}", 
                    normalizedCustomerPhone, customerAccountNumber, request.getServiceType());
            } else {
                // For other service types (TV, RRA, ELECTRICITY), customerAccountNumber is required
                throw new RuntimeException("customerAccountNumber is required for service type: " + request.getServiceType() + 
                    ". Please provide Decoder number (TV), TIN number (RRA), or Cashpower number (ELECTRICITY)");
            }
        }
        
        transaction.setCustomerAccountNumber(customerAccountNumber);
        logger.info("Customer account number for EFASHE: {} (Service: {})", customerAccountNumber, request.getServiceType());
        transaction.setAmount(amount);
        transaction.setCurrency(request.getCurrency());
        transaction.setMopayTransactionId(moPayResponse != null ? moPayResponse.getTransactionId() : null);
        transaction.setMopayStatus(moPayResponse != null && moPayResponse.getStatus() != null 
            ? moPayResponse.getStatus().toString() : "PENDING");
        transaction.setEfasheStatus("PENDING");
        transaction.setMessage(moPayRequest.getMessage());
        
        // Store cashback amounts and phone numbers for reference
        // NOTE: NO transfers in initial MoPay request
        // ALL transfers (full amount + customer cashback + besoft share) will be sent ONLY after EFASHE status is SUCCESS
        transaction.setCustomerCashbackAmount(customerCashbackAmount);
        transaction.setBesoftShareAmount(besoftShareAmount);
        transaction.setFullAmountPhone(normalizePhoneTo12Digits(settingsResponse.getFullAmountPhoneNumber()));
        transaction.setCashbackPhone(normalizePhoneTo12Digits(settingsResponse.getCashbackPhoneNumber()));
        transaction.setCashbackSent(false); // Not sent yet - will be sent only when EFASHE status is SUCCESS
        // Store fullAmountPhoneReceives for later transfer
        // We need to calculate this and store it, but we'll calculate it in sendCashbackTransfers
        
        // Save transaction
        efasheTransactionRepository.save(transaction);
        logger.info("Saved EFASHE transaction record - Transaction ID: {}", transactionId);

        // Build response - normalize all phone numbers to consistent format
        String normalizedFullAmountPhone = normalizePhoneTo12Digits(settingsResponse.getFullAmountPhoneNumber());
        EfasheInitiateResponse response = new EfasheInitiateResponse();
        response.setTransactionId(transactionId);
        response.setServiceType(request.getServiceType());
        response.setAmount(amount);
        // Set normalized phone as string in response
        response.setCustomerPhone(normalizedCustomerPhone);
        response.setMoPayResponse(moPayResponse);
        // Return normalized phone numbers in response (12 digits with 250 prefix)
        response.setFullAmountPhone(normalizedFullAmountPhone);
        response.setCashbackPhone(normalizePhoneTo12Digits(settingsResponse.getCashbackPhoneNumber()));
        response.setCustomerCashbackAmount(customerCashbackAmount);
        response.setAgentCommissionAmount(agentCommissionAmount);
        response.setBesoftShareAmount(besoftShareAmount);
        response.setFullAmountPhoneReceives(fullAmountPhoneReceives);

        logger.info("EFASHE payment initiated successfully - MoPay Transaction ID: {}", 
            moPayResponse != null ? moPayResponse.getTransactionId() : "N/A");

        return response;
    }

    /**
     * Check the status of an EFASHE transaction using MoPay transaction ID
     * If status is SUCCESS (200), automatically triggers EFASHE validate and execute
     * @param transactionId The EFASHE transaction ID
     * @return EfasheStatusResponse containing the transaction status and EFASHE responses
     */
    @Transactional
    public EfasheStatusResponse checkTransactionStatus(String transactionId) {
        logger.info("Checking EFASHE transaction status for transaction ID: {}", transactionId);
        
        // Get stored transaction record
        EfasheTransaction transaction = efasheTransactionRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new RuntimeException("EFASHE transaction not found: " + transactionId));
        
        // Check MoPay status
        MoPayResponse moPayResponse = moPayService.checkTransactionStatus(transactionId);
        
        EfasheValidateResponse validateResponse = null;
        EfasheExecuteResponse executeResponse = null;
        
        // Update MoPay status in transaction record
        if (moPayResponse != null && moPayResponse.getStatus() != null) {
            Integer statusCode = moPayResponse.getStatus();
            transaction.setMopayStatus(statusCode.toString());
            transaction.setMopayTransactionId(moPayResponse.getTransactionId());
            
            logger.info("MoPay status check - Status: {}, Success: {}, Transaction ID: {}", 
                statusCode, moPayResponse.getSuccess(), moPayResponse.getTransactionId());
            
            // If MoPay status is SUCCESS (200 or 201) OR success flag is true, trigger EFASHE validate and execute
            boolean isSuccess = (statusCode != null && (statusCode == 200 || statusCode == 201)) 
                || (moPayResponse.getSuccess() != null && moPayResponse.getSuccess());
            
            if (isSuccess && !"SUCCESS".equalsIgnoreCase(transaction.getEfasheStatus())) {
                logger.info("MoPay transaction SUCCESS detected (status: {}, success: {}). Triggering EFASHE validate and execute...", 
                    statusCode, moPayResponse.getSuccess());
                
                try {
                    // Step 1: Validate with EFASHE
                    EfasheValidateRequest validateRequest = new EfasheValidateRequest();
                    validateRequest.setVerticalId(getVerticalId(transaction.getServiceType()));
                    validateRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                    
                    logger.info("Calling EFASHE validate - Vertical: {}, Customer Account Number: {}", 
                        validateRequest.getVerticalId(), validateRequest.getCustomerAccountNumber());
                    validateResponse = efasheApiService.validateAccount(validateRequest);
                    
                    if (validateResponse != null && validateResponse.getTrxId() != null) {
                        transaction.setTrxId(validateResponse.getTrxId());
                        logger.info("EFASHE validate successful - TrxId: {}", validateResponse.getTrxId());
                        
                        // Step 2: Execute EFASHE transaction
                        EfasheExecuteRequest executeRequest = new EfasheExecuteRequest();
                        executeRequest.setTrxId(validateResponse.getTrxId());
                        executeRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                        executeRequest.setAmount(transaction.getAmount());
                        executeRequest.setVerticalId(getVerticalId(transaction.getServiceType()));
                        // Set deliveryMethodId based on service type: "sms" for ELECTRICITY and RRA, "direct_topup" for others
                        String deliveryMethodId = (transaction.getServiceType() == EfasheServiceType.ELECTRICITY || 
                            transaction.getServiceType() == EfasheServiceType.RRA) ? "sms" : "direct_topup";
                        executeRequest.setDeliveryMethodId(deliveryMethodId);
                        // Save deliveryMethodId to transaction for reference
                        transaction.setDeliveryMethodId(deliveryMethodId);
                        logger.info("Set deliveryMethodId: {} for service type: {}", deliveryMethodId, transaction.getServiceType());
                        
                        // Set deliverTo when deliveryMethodId is "sms" (required for ELECTRICITY and RRA)
                        // deliverTo should be the customer's phone number for SMS delivery
                        if ("sms".equals(deliveryMethodId)) {
                            // Use customer phone number for SMS delivery
                            // Normalize phone to 12 digits format (250XXXXXXXXX)
                            String normalizedPhone = normalizePhoneTo12Digits(transaction.getCustomerPhone());
                            // For SMS delivery, EFASHE might expect phone without 250 prefix or in a specific format
                            // Try without 250 prefix first (0784638201 format)
                            String deliverToPhone = normalizedPhone.startsWith("250") 
                                ? "0" + normalizedPhone.substring(3) 
                                : normalizedPhone;
                            executeRequest.setDeliverTo(deliverToPhone);
                            logger.info("Set deliverTo (SMS): {} for service type: {} (normalized from: {})", 
                                deliverToPhone, transaction.getServiceType(), normalizedPhone);
                        }
                        // deliverTo and callBack are optional for direct_topup
                        
                        logger.info("Calling EFASHE execute - TrxId: {}, Amount: {}, Account: {}", 
                            executeRequest.getTrxId(), executeRequest.getAmount(), executeRequest.getCustomerAccountNumber());
                        executeResponse = efasheApiService.executeTransaction(executeRequest);
                        
                        if (executeResponse != null) {
                            // EFASHE execute returns async response with pollEndpoint
                            transaction.setPollEndpoint(executeResponse.getPollEndpoint());
                            transaction.setRetryAfterSecs(executeResponse.getRetryAfterSecs());
                            
                            // If /vend/execute returns HTTP 200 or 202, set status to SUCCESS immediately
                            // HTTP 200/202 means the execute request was successful, regardless of pollEndpoint
                            // Skip polling - treat as SUCCESS immediately
                            if (executeResponse.getHttpStatusCode() != null && 
                                (executeResponse.getHttpStatusCode() == 200 || executeResponse.getHttpStatusCode() == 202)) {
                                transaction.setEfasheStatus("SUCCESS");
                                transaction.setMessage(executeResponse.getMessage() != null ? executeResponse.getMessage() : 
                                    "EFASHE transaction executed successfully (HTTP " + executeResponse.getHttpStatusCode() + ")");
                                logger.info("EFASHE execute returned HTTP {} - Setting status to SUCCESS immediately. Skipping polling. PollEndpoint: {}, RetryAfterSecs: {}", 
                                    executeResponse.getHttpStatusCode(), executeResponse.getPollEndpoint(), executeResponse.getRetryAfterSecs());
                                
                                // Save transaction before sending cashback transfers
                                efasheTransactionRepository.save(transaction);
                                
                                // Send cashback transfers ONLY when EFASHE status is SUCCESS
                                logger.info("EFASHE status is SUCCESS - Sending cashback transfers (customer and besoft)");
                                try {
                                    sendCashbackTransfers(transaction, transactionId);
                                    logger.info("Cashback transfers sent successfully after EFASHE SUCCESS");
                                } catch (Exception cashbackException) {
                                    logger.error("Failed to send cashback transfers after EFASHE SUCCESS: ", cashbackException);
                                    // Don't fail the transaction if cashback transfers fail
                                }
                                
                                // For ELECTRICITY service, get token and send it to user
                                if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                                    logger.info("=== ELECTRICITY transaction - Retrieving token ===");
                                    try {
                                        ElectricityTokenResponse tokenResponse = efasheApiService.getElectricityTokens(
                                            transaction.getCustomerAccountNumber(), 1);
                                        
                                        if (tokenResponse != null && tokenResponse.getData() != null && 
                                            !tokenResponse.getData().isEmpty()) {
                                            ElectricityTokenResponse.TokenData tokenData = tokenResponse.getData().get(0);
                                            String token = tokenData.getToken();
                                            
                                            if (token != null && !token.trim().isEmpty()) {
                                                logger.info("Electricity token retrieved: {}", token);
                                                // Send token via WhatsApp or SMS
                                                sendElectricityToken(transaction, token, tokenData);
                                            } else {
                                                logger.warn("Electricity token is empty in response");
                                            }
                                        } else {
                                            logger.warn("No token data returned from electricity tokens endpoint");
                                        }
                                    } catch (Exception e) {
                                        logger.error("Error retrieving electricity token: ", e);
                                        // Don't fail the transaction if token retrieval fails
                                    }
                                }
                                
                                // Send WhatsApp notification since transaction is SUCCESS
                                logger.info("=== STARTING WhatsApp Notification for Transaction: {} ===", transaction.getTransactionId());
                                sendWhatsAppNotification(transaction);
                                logger.info("=== COMPLETED WhatsApp Notification for Transaction: {} ===", transaction.getTransactionId());
                                
                                // Continue to log pollEndpoint for reference (even though status is already SUCCESS and we skip polling)
                                if (executeResponse.getPollEndpoint() != null && !executeResponse.getPollEndpoint().isEmpty()) {
                                    logger.info("Poll endpoint available for future reference (but skipping polling): {}", executeResponse.getPollEndpoint());
                                }
                            } else if (executeResponse.getPollEndpoint() != null && !executeResponse.getPollEndpoint().isEmpty()) {
                                // If pollEndpoint is provided but HTTP status is not 200, we need to poll for status
                                transaction.setEfasheStatus("PENDING");
                                transaction.setMessage("EFASHE transaction initiated. Poll endpoint: " + executeResponse.getPollEndpoint());
                                logger.info("EFASHE execute initiated async - PollEndpoint: {}, RetryAfterSecs: {}s", 
                                    executeResponse.getPollEndpoint(), executeResponse.getRetryAfterSecs());
                                
                                // Wait for retryAfterSecs before polling (if specified)
                                if (executeResponse.getRetryAfterSecs() != null && executeResponse.getRetryAfterSecs() > 0) {
                                    try {
                                        logger.info("Waiting {} seconds before polling EFASHE status...", executeResponse.getRetryAfterSecs());
                                        Thread.sleep(executeResponse.getRetryAfterSecs() * 1000L);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        logger.warn("Interrupted while waiting to poll EFASHE status");
                                    }
                                }
                                
                                // Poll the status endpoint to check if transaction is SUCCESS
                                try {
                                    EfashePollStatusResponse pollResponse = efasheApiService.pollTransactionStatus(executeResponse.getPollEndpoint());
                                    
                                    if (pollResponse != null) {
                                        String pollStatus = pollResponse.getStatus();
                                        logger.info("EFASHE poll response received - Status: {}, Message: {}, TrxId: {}", 
                                            pollStatus, pollResponse.getMessage(), pollResponse.getTrxId());
                                        
                                        // Check if status is SUCCESS (case-insensitive, trim whitespace)
                                        String trimmedStatus = pollStatus != null ? pollStatus.trim() : null;
                                        if (trimmedStatus != null && "SUCCESS".equalsIgnoreCase(trimmedStatus)) {
                                            transaction.setEfasheStatus("SUCCESS");
                                            transaction.setMessage(pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction completed successfully");
                                            logger.info("EFASHE transaction SUCCESS confirmed from poll - Status: '{}'", trimmedStatus);
                                            
                                            // Save transaction before sending cashback transfers
                                            efasheTransactionRepository.save(transaction);
                                            
                                            // Send cashback transfers ONLY when EFASHE status is SUCCESS
                                            logger.info("EFASHE status is SUCCESS - Sending cashback transfers (customer and besoft)");
                                            try {
                                                sendCashbackTransfers(transaction, transactionId);
                                                logger.info("Cashback transfers sent successfully after EFASHE SUCCESS");
                                            } catch (Exception cashbackException) {
                                                logger.error("Failed to send cashback transfers after EFASHE SUCCESS: ", cashbackException);
                                                // Don't fail the transaction if cashback transfers fail
                                            }
                                            
                                            // For ELECTRICITY service, get token and send it to user
                                            if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                                                logger.info("=== ELECTRICITY transaction - Retrieving token ===");
                                                try {
                                                    ElectricityTokenResponse tokenResponse = efasheApiService.getElectricityTokens(
                                                        transaction.getCustomerAccountNumber(), 1);
                                                    
                                                    if (tokenResponse != null && tokenResponse.getData() != null && 
                                                        !tokenResponse.getData().isEmpty()) {
                                                        ElectricityTokenResponse.TokenData tokenData = tokenResponse.getData().get(0);
                                                        String token = tokenData.getToken();
                                                        
                                                        if (token != null && !token.trim().isEmpty()) {
                                                            logger.info("Electricity token retrieved: {}", token);
                                                            // Send token via WhatsApp or SMS
                                                            sendElectricityToken(transaction, token, tokenData);
                                                        } else {
                                                            logger.warn("Electricity token is empty in response");
                                                        }
                                                    } else {
                                                        logger.warn("No token data returned from electricity tokens endpoint");
                                                    }
                                                } catch (Exception e) {
                                                    logger.error("Error retrieving electricity token: ", e);
                                                    // Don't fail the transaction if token retrieval fails
                                                }
                                            }
                                            
                                            // Send WhatsApp notification
                                            sendWhatsAppNotification(transaction);
                                        } else {
                                            // Still pending or failed - log the actual status value
                                            String actualStatus = trimmedStatus != null ? trimmedStatus : "PENDING";
                                            transaction.setEfasheStatus(actualStatus);
                                            transaction.setMessage(pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction is still processing");
                                            logger.info("EFASHE transaction status from poll - Status: '{}' (not SUCCESS yet, will need to check again)", actualStatus);
                                        }
                                    } else {
                                        logger.warn("EFASHE poll response is null, keeping status as PENDING");
                                        transaction.setEfasheStatus("PENDING");
                                        transaction.setMessage("EFASHE transaction is still processing");
                                    }
                                } catch (Exception e) {
                                    logger.warn("Error polling EFASHE status, will remain PENDING: ", e);
                                    // Keep status as PENDING if poll fails, can be checked again later
                                }
                            } else {
                                // Synchronous response - check if status is SUCCESS
                                String efasheStatus = executeResponse.getStatus() != null ? executeResponse.getStatus() : "SUCCESS";
                                transaction.setEfasheStatus(efasheStatus);
                                transaction.setMessage(executeResponse.getMessage() != null ? executeResponse.getMessage() : "EFASHE transaction executed successfully");
                                logger.info("EFASHE execute synchronous response - Status: {}, Message: {}", 
                                    executeResponse.getStatus(), executeResponse.getMessage());
                                
                                // Only send cashback transfers and WhatsApp notification if status is SUCCESS
                                if ("SUCCESS".equalsIgnoreCase(efasheStatus)) {
                                    logger.info("EFASHE transaction SUCCESS confirmed - Sending cashback transfers");
                                    
                                    // Save transaction before sending cashback transfers
                                    efasheTransactionRepository.save(transaction);
                                    
                                    // Send cashback transfers ONLY when EFASHE status is SUCCESS
                                    try {
                                        sendCashbackTransfers(transaction, transactionId);
                                        logger.info("Cashback transfers sent successfully after EFASHE SUCCESS");
                                    } catch (Exception cashbackException) {
                                        logger.error("Failed to send cashback transfers after EFASHE SUCCESS: ", cashbackException);
                                        // Don't fail the transaction if cashback transfers fail
                                    }
                                    
                                    // Send WhatsApp notification
                                    sendWhatsAppNotification(transaction);
                                } else {
                                    logger.info("EFASHE transaction status is not SUCCESS: {}, cashback transfers will NOT be sent", efasheStatus);
                                }
                            }
                        } else {
                            transaction.setEfasheStatus("FAILED");
                            transaction.setErrorMessage("EFASHE execute returned null response");
                            logger.error("EFASHE execute returned null response");
                        }
                    } else {
                        transaction.setEfasheStatus("FAILED");
                        transaction.setErrorMessage("EFASHE validate did not return trxId. Response: " + (validateResponse != null ? validateResponse.toString() : "null"));
                        logger.error("EFASHE validate did not return trxId. Response: {}", validateResponse);
                    }
                } catch (Exception e) {
                    transaction.setEfasheStatus("FAILED");
                    transaction.setErrorMessage("Failed to execute EFASHE transaction: " + e.getMessage());
                    logger.error("Error executing EFASHE transaction after MoPay SUCCESS: ", e);
                    e.printStackTrace();
                    // Save transaction with FAILED status
                    efasheTransactionRepository.save(transaction);
                    logger.info("Transaction saved with FAILED status due to validation/execution error");
                }
            } else {
                logger.info("MoPay transaction not yet SUCCESS or already processed - Status: {}, EFASHE Status: {}", 
                    statusCode, transaction.getEfasheStatus());
                
                // If EFASHE is PENDING with pollEndpoint, check status again
                // NOTE: If /vend/execute returned HTTP 200, status is already SUCCESS, so we don't poll
                // Only poll if status is still PENDING and we have a pollEndpoint
                if ("PENDING".equalsIgnoreCase(transaction.getEfasheStatus()) 
                    && transaction.getPollEndpoint() != null 
                    && !transaction.getPollEndpoint().isEmpty()
                    && (transaction.getCashbackSent() == null || !transaction.getCashbackSent())) {
                    logger.info("Checking EFASHE poll status for pending transaction - PollEndpoint: {}", transaction.getPollEndpoint());
                    try {
                        EfashePollStatusResponse pollResponse = efasheApiService.pollTransactionStatus(transaction.getPollEndpoint());
                        
                        if (pollResponse != null) {
                            String pollStatus = pollResponse.getStatus();
                            logger.info("EFASHE poll response received - Status: '{}', Message: {}, TrxId: {}", 
                                pollStatus, pollResponse.getMessage(), pollResponse.getTrxId());
                            
                            // Check if status is SUCCESS (case-insensitive, trim whitespace)
                            if (pollStatus != null && "SUCCESS".equalsIgnoreCase(pollStatus.trim())) {
                                transaction.setEfasheStatus("SUCCESS");
                                transaction.setMessage(pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction completed successfully");
                                logger.info("EFASHE transaction SUCCESS confirmed from poll - Status: {}", pollStatus);
                                
                                // Save transaction before sending cashback transfers
                                efasheTransactionRepository.save(transaction);
                                
                                // Send cashback transfers ONLY when EFASHE status is SUCCESS
                                logger.info("EFASHE status is SUCCESS - Sending cashback transfers (customer and besoft)");
                                try {
                                    sendCashbackTransfers(transaction, transactionId);
                                    logger.info("Cashback transfers sent successfully after EFASHE SUCCESS");
                                } catch (Exception cashbackException) {
                                    logger.error("Failed to send cashback transfers after EFASHE SUCCESS: ", cashbackException);
                                    // Don't fail the transaction if cashback transfers fail
                                }
                                
                                // For ELECTRICITY service, get token and send it to user
                                if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                                    logger.info("=== ELECTRICITY transaction - Retrieving token ===");
                                    try {
                                        ElectricityTokenResponse tokenResponse = efasheApiService.getElectricityTokens(
                                            transaction.getCustomerAccountNumber(), 1);
                                        
                                        if (tokenResponse != null && tokenResponse.getData() != null && 
                                            !tokenResponse.getData().isEmpty()) {
                                            ElectricityTokenResponse.TokenData tokenData = tokenResponse.getData().get(0);
                                            String token = tokenData.getToken();
                                            
                                            if (token != null && !token.trim().isEmpty()) {
                                                logger.info("Electricity token retrieved: {}", token);
                                                // Send token via WhatsApp or SMS
                                                sendElectricityToken(transaction, token, tokenData);
                                            } else {
                                                logger.warn("Electricity token is empty in response");
                                            }
                                        } else {
                                            logger.warn("No token data returned from electricity tokens endpoint");
                                        }
                                    } catch (Exception e) {
                                        logger.error("Error retrieving electricity token: ", e);
                                        // Don't fail the transaction if token retrieval fails
                                    }
                                }
                                
                                // Send WhatsApp notification
                                sendWhatsAppNotification(transaction);
                            } else if (pollStatus != null) {
                                // Update status (could be FAILED or still PENDING)
                                String actualStatus = pollStatus.trim();
                                transaction.setEfasheStatus(actualStatus);
                                transaction.setMessage(pollResponse.getMessage());
                                logger.info("EFASHE poll status updated - Status: '{}' (not SUCCESS yet)", actualStatus);
                            } else {
                                logger.warn("EFASHE poll response status is null, keeping status as PENDING");
                            }
                        } else {
                            logger.warn("EFASHE poll response is null, keeping status as PENDING");
                        }
                    } catch (Exception e) {
                        logger.error("Error polling EFASHE status for pending transaction: ", e);
                        e.printStackTrace();
                        // Keep status as PENDING if poll fails
                    }
                }
            }
            
            // Save updated transaction
            efasheTransactionRepository.save(transaction);
            logger.info("Updated EFASHE transaction record - MoPay Status: {}, EFASHE Status: {}, Cashback Sent: {}", 
                transaction.getMopayStatus(), transaction.getEfasheStatus(), transaction.getCashbackSent());
        }
        
        // Build response with all information
        EfasheStatusResponse response = new EfasheStatusResponse();
        response.setMoPayResponse(moPayResponse);
        response.setValidateResponse(validateResponse);
        response.setExecuteResponse(executeResponse);
        response.setTransactionId(transactionId);
        response.setMopayStatus(transaction.getMopayStatus());
        response.setEfasheStatus(transaction.getEfasheStatus());
        response.setMessage(transaction.getMessage());
        response.setPollEndpoint(transaction.getPollEndpoint());
        response.setRetryAfterSecs(transaction.getRetryAfterSecs());
        
        // Set success field: true if EFASHE status is SUCCESS, false otherwise
        boolean isSuccess = "SUCCESS".equalsIgnoreCase(transaction.getEfasheStatus());
        response.setSuccess(isSuccess);
        logger.info("Response success field set to: {} (EFASHE status: {})", isSuccess, transaction.getEfasheStatus());
        
        // Build transfers list - include full amount transfer and cashback transfers
        List<EfasheStatusResponse.TransferInfo> transfers = new ArrayList<>();
        
        // Transfer 1: Full Amount Phone receives remaining amount
        // This transfer is part of the main MoPay request during initiate
        if (transaction.getFullAmountPhone() != null && transaction.getAmount() != null) {
            EfasheStatusResponse.TransferInfo fullAmountTransfer = new EfasheStatusResponse.TransferInfo();
            fullAmountTransfer.setType("FULL_AMOUNT");
            fullAmountTransfer.setFromPhone(transaction.getCustomerPhone());
            fullAmountTransfer.setToPhone(transaction.getFullAmountPhone());
            // Calculate amount: total - customer cashback - besoft share
            BigDecimal fullAmount = transaction.getAmount()
                .subtract(transaction.getCustomerCashbackAmount() != null ? transaction.getCustomerCashbackAmount() : BigDecimal.ZERO)
                .subtract(transaction.getBesoftShareAmount() != null ? transaction.getBesoftShareAmount() : BigDecimal.ZERO);
            fullAmountTransfer.setAmount(fullAmount);
            fullAmountTransfer.setMessage("EFASHE " + transaction.getServiceType() + " - Full amount");
            fullAmountTransfer.setTransactionId(transactionId); // Main transaction ID
            transfers.add(fullAmountTransfer);
        }
        
        // Transfer 2: Customer Cashback - ONLY show if EFASHE status is SUCCESS and cashback was sent
        // If status is FAILED, do NOT show cashback transfers (they were never sent)
        if (isSuccess && transaction.getCustomerCashbackAmount() != null 
            && transaction.getCustomerCashbackAmount().compareTo(BigDecimal.ZERO) > 0
            && transaction.getCashbackSent() != null && transaction.getCashbackSent()) {
            EfasheStatusResponse.TransferInfo customerCashbackTransfer = new EfasheStatusResponse.TransferInfo();
            customerCashbackTransfer.setType("CUSTOMER_CASHBACK");
            customerCashbackTransfer.setFromPhone(transaction.getFullAmountPhone());
            customerCashbackTransfer.setToPhone(transaction.getCustomerPhone());
            customerCashbackTransfer.setAmount(transaction.getCustomerCashbackAmount());
            customerCashbackTransfer.setMessage("EFASHE " + transaction.getServiceType() + " - Customer cashback");
            customerCashbackTransfer.setTransactionId(transactionId); // Use same transaction ID as main transaction
            transfers.add(customerCashbackTransfer);
            logger.info("Added customer cashback transfer to response - Amount: {}, From: {}, To: {}, Sent: {}", 
                transaction.getCustomerCashbackAmount(), transaction.getFullAmountPhone(), 
                transaction.getCustomerPhone(), transaction.getCashbackSent());
        }
        
        // Transfer 3: Besoft Share - ONLY show if EFASHE status is SUCCESS and cashback was sent
        // If status is FAILED, do NOT show cashback transfers (they were never sent)
        if (isSuccess && transaction.getBesoftShareAmount() != null 
            && transaction.getBesoftShareAmount().compareTo(BigDecimal.ZERO) > 0
            && transaction.getCashbackSent() != null && transaction.getCashbackSent()) {
            EfasheStatusResponse.TransferInfo besoftShareTransfer = new EfasheStatusResponse.TransferInfo();
            besoftShareTransfer.setType("BESOFT_SHARE");
            besoftShareTransfer.setFromPhone(transaction.getFullAmountPhone());
            besoftShareTransfer.setToPhone(transaction.getCashbackPhone());
            besoftShareTransfer.setAmount(transaction.getBesoftShareAmount());
            besoftShareTransfer.setMessage("EFASHE " + transaction.getServiceType() + " - Besoft share");
            besoftShareTransfer.setTransactionId(transactionId); // Use same transaction ID as main transaction
            transfers.add(besoftShareTransfer);
            logger.info("Added besoft share transfer to response - Amount: {}, From: {}, To: {}, Sent: {}", 
                transaction.getBesoftShareAmount(), transaction.getFullAmountPhone(), 
                transaction.getCashbackPhone(), transaction.getCashbackSent());
        }
        
        response.setTransfers(transfers);
        
        logger.info("Transaction status check result - Transaction ID: {}, MoPay Status: {}, EFASHE Status: {}, Transfers: {}", 
            transactionId, transaction.getMopayStatus(), transaction.getEfasheStatus(), transfers.size());
        return response;
    }
    
    /**
     * Send cashback transfers to customer and besoft phone after EFASHE execute completes
     * This creates separate MoPay payment requests for each cashback transfer
     * Uses the MoPay transaction ID from the initial payment initiation
     */
    /**
     * Send ALL transfers (full amount + customer cashback + besoft share) in a SINGLE MoPay request
     * All transfers use the same transaction ID (the main MoPay transaction ID)
     * This is called ONLY after EFASHE status is SUCCESS
     * This allows MoPay to accept multiple transfers with the same transaction ID in one request
     */
    private void sendCashbackTransfers(EfasheTransaction transaction, String mainTransactionId) {
        logger.info("=== sendAllTransfers START (Full Amount + Customer Cashback + Besoft Share) ===");
        logger.info("Transaction ID: {}", transaction.getTransactionId());
        logger.info("MoPay Transaction ID: {}", transaction.getMopayTransactionId());
        logger.info("Cashback Sent Flag: {}", transaction.getCashbackSent());
        logger.info("Total Amount: {}", transaction.getAmount());
        logger.info("Customer Cashback Amount: {}", transaction.getCustomerCashbackAmount());
        logger.info("Besoft Share Amount: {}", transaction.getBesoftShareAmount());
        logger.info("Full Amount Phone: {}", transaction.getFullAmountPhone());
        logger.info("Customer Phone: {}", transaction.getCustomerPhone());
        logger.info("Cashback Phone: {}", transaction.getCashbackPhone());
        
        if (transaction.getCashbackSent() != null && transaction.getCashbackSent()) {
            logger.info("All transfers already sent for transaction: {}, skipping", transaction.getTransactionId());
            logger.info("=== sendAllTransfers END (already sent) ===");
            return;
        }
        
        // Use the SAME transaction ID from initial MoPay payment for the transfers request
        // This is the transaction ID that was used in the initial payment
        // All transfers in the array will use this same transaction ID (set on main request, not on individual transfers)
        String transactionIdToUse = transaction.getMopayTransactionId() != null && !transaction.getMopayTransactionId().isEmpty()
            ? transaction.getMopayTransactionId()
            : mainTransactionId;
        
        logger.info("Using transaction ID for ALL transfers (main request and array): {} (MoPay: {}, EFASHE: {})", 
            transactionIdToUse, transaction.getMopayTransactionId(), mainTransactionId);
        
        try {
            String normalizedFullAmountPhone = normalizePhoneTo12Digits(transaction.getFullAmountPhone());
            String normalizedCustomerPhone = normalizePhoneTo12Digits(transaction.getCustomerPhone());
            String normalizedBesoftPhone = normalizePhoneTo12Digits(transaction.getCashbackPhone());
            
            // Calculate full amount phone receives: amount - customerCashback - besoftShare
            BigDecimal customerCashbackAmount = transaction.getCustomerCashbackAmount() != null ? transaction.getCustomerCashbackAmount() : BigDecimal.ZERO;
            BigDecimal besoftShareAmount = transaction.getBesoftShareAmount() != null ? transaction.getBesoftShareAmount() : BigDecimal.ZERO;
            BigDecimal fullAmountPhoneReceives = transaction.getAmount()
                .subtract(customerCashbackAmount)
                .subtract(besoftShareAmount)
                .setScale(0, RoundingMode.HALF_UP);
            
            logger.info("Calculated full amount phone receives: {} (Total: {} - Customer Cashback: {} - Besoft Share: {})", 
                fullAmountPhoneReceives, transaction.getAmount(), customerCashbackAmount, besoftShareAmount);
            
            // Build transfers array - NO transaction_id on individual transfers
            // The transaction_id is set on the main request, and all transfers inherit it
            List<MoPayInitiateRequest.Transfer> transfers = new ArrayList<>();
            BigDecimal totalTransferAmount = BigDecimal.ZERO;
            
            // Transfer 1: Full Amount Phone - receives amount minus customer cashback and besoft share
            if (fullAmountPhoneReceives.compareTo(BigDecimal.ZERO) > 0) {
                MoPayInitiateRequest.Transfer fullAmountTransfer = new MoPayInitiateRequest.Transfer();
                // NO transaction_id on individual transfers - they inherit from main request
                fullAmountTransfer.setAmount(fullAmountPhoneReceives);
                fullAmountTransfer.setPhone(Long.parseLong(normalizedFullAmountPhone));
                fullAmountTransfer.setMessage("EFASHE " + transaction.getServiceType() + " - Full amount");
                transfers.add(fullAmountTransfer);
                totalTransferAmount = totalTransferAmount.add(fullAmountPhoneReceives);
                logger.info("Added full amount transfer to array - Amount: {}, To: {}", 
                    fullAmountPhoneReceives, normalizedFullAmountPhone);
            }
            
            // Transfer 2: Customer Cashback
            if (customerCashbackAmount.compareTo(BigDecimal.ZERO) > 0) {
                MoPayInitiateRequest.Transfer customerTransfer = new MoPayInitiateRequest.Transfer();
                // NO transaction_id on individual transfers - they inherit from main request
                customerTransfer.setAmount(customerCashbackAmount);
                customerTransfer.setPhone(Long.parseLong(normalizedCustomerPhone));
                customerTransfer.setMessage("EFASHE " + transaction.getServiceType() + " - Customer cashback");
                transfers.add(customerTransfer);
                totalTransferAmount = totalTransferAmount.add(customerCashbackAmount);
                logger.info("Added customer cashback transfer to array - Amount: {}, To: {}", 
                    customerCashbackAmount, normalizedCustomerPhone);
            }
            
            // Transfer 3: Besoft Share
            if (besoftShareAmount.compareTo(BigDecimal.ZERO) > 0) {
                MoPayInitiateRequest.Transfer besoftTransfer = new MoPayInitiateRequest.Transfer();
                // NO transaction_id on individual transfers - they inherit from main request
                besoftTransfer.setAmount(besoftShareAmount);
                besoftTransfer.setPhone(Long.parseLong(normalizedBesoftPhone));
                besoftTransfer.setMessage("EFASHE " + transaction.getServiceType() + " - Besoft share");
                transfers.add(besoftTransfer);
                totalTransferAmount = totalTransferAmount.add(besoftShareAmount);
                logger.info("Added Besoft share transfer to array - Amount: {}, To: {}", 
                    besoftShareAmount, normalizedBesoftPhone);
            }
            
            if (transfers.isEmpty()) {
                logger.info("No transfers to send (all amounts are 0 or null)");
                transaction.setCashbackSent(true);
                efasheTransactionRepository.save(transaction);
                return;
            }
            
            // Build SINGLE MoPay request with ALL transfers in the transfers array
            // Main request uses the SAME transaction ID from initial payment
            // All transfers in array inherit this transaction ID
            MoPayInitiateRequest allTransfersRequest = new MoPayInitiateRequest();
            allTransfersRequest.setTransaction_id(transactionIdToUse); // SAME transaction ID from initial payment
            allTransfersRequest.setAmount(totalTransferAmount); // Total amount of all transfers
            allTransfersRequest.setCurrency("RWF");
            allTransfersRequest.setPhone(normalizedCustomerPhone); // From phone (customer who paid)
            allTransfersRequest.setPayment_mode("MOBILE");
            allTransfersRequest.setMessage("EFASHE " + transaction.getServiceType() + " - All transfers");
            allTransfersRequest.setTransfers(transfers); // All transfers in one array, all using same transaction ID
            
            logger.info("=== Sending ALL transfers (Full Amount + Customer Cashback + Besoft Share) in SINGLE MoPay request ===");
            logger.info("Transaction ID (same for main request and all transfers): {}", transactionIdToUse);
            logger.info("Total Transfer Amount: {}", totalTransferAmount);
            logger.info("From Phone (customer): {}", normalizedCustomerPhone);
            logger.info("Number of transfers: {}", transfers.size());
            for (int i = 0; i < transfers.size(); i++) {
                MoPayInitiateRequest.Transfer t = transfers.get(i);
                logger.info("  Transfer {}: Amount={}, To={} (Transaction ID inherited from main request: {})", 
                    i + 1, t.getAmount(), t.getPhone(), transactionIdToUse);
            }
            
            // Make SINGLE MoPay API call with all transfers
            MoPayResponse transfersResponse = moPayService.initiatePayment(allTransfersRequest);
            logger.info("MoPayService.initiatePayment returned - Success: {}, Status: {}, Transaction ID: {}", 
                transfersResponse != null ? transfersResponse.getSuccess() : null,
                transfersResponse != null ? transfersResponse.getStatus() : null,
                transfersResponse != null ? transfersResponse.getTransactionId() : null);
            
            boolean transfersSuccessful = transfersResponse != null && transfersResponse.getSuccess() != null && transfersResponse.getSuccess();
            
            // Mark as sent
            transaction.setCashbackSent(true);
            
            if (transfersSuccessful) {
                transaction.setMessage(transaction.getMessage() != null ? transaction.getMessage() + " All transfers sent (full amount + customer cashback + besoft share)." : 
                    "EFASHE transaction completed successfully. All transfers sent (full amount + customer cashback + besoft share).");
                logger.info("✅ All transfers sent successfully in single request - Transaction ID: {}", transactionIdToUse);
            } else {
                transaction.setMessage(transaction.getMessage() != null ? transaction.getMessage() + " Transfers failed." : 
                    "EFASHE transaction completed, but transfers failed.");
                logger.error("❌ Transfers failed - Transaction ID: {}, Response: {}", transactionIdToUse, transfersResponse);
            }
            
            efasheTransactionRepository.save(transaction);
            logger.info("All transfers completed for transaction: {} - Success: {}, EFASHE Status: {}", 
                transaction.getTransactionId(), transfersSuccessful, transaction.getEfasheStatus());
            logger.info("=== sendAllTransfers END ===");
        } catch (Exception e) {
            logger.error("=== ERROR in sendAllTransfers ===");
            logger.error("Transaction ID: {}", transaction.getTransactionId());
            logger.error("Error sending all transfers for transaction {}: ", transaction.getTransactionId(), e);
            e.printStackTrace();
            logger.error("=== END ERROR ===");
            // Mark as sent to avoid infinite retries
            transaction.setCashbackSent(true);
            efasheTransactionRepository.save(transaction);
        }
    }
    
    /**
     * Convert EfasheServiceType to EFASHE verticalId
     * Based on actual EFASHE API verticals:
     * - airtime: Airtime
     * - ers: Voice and Data Bundles (for MTN)
     * - paytv: Pay Television (for TV)
     * - tax: RRA Taxes (for RRA)
     * - electricity: Electricity
     */
    private String getVerticalId(EfasheServiceType serviceType) {
        if (serviceType == null) {
            return "airtime"; // Default
        }
        switch (serviceType) {
            case AIRTIME:
                return "airtime";
            case MTN:
                return "ers"; // MTN uses "ers" (Voice and Data Bundles)
            case RRA:
                return "tax"; // RRA uses "tax" as verticalId
            case TV:
                return "paytv"; // TV uses "paytv" (Pay Television)
            case ELECTRICITY:
                return "electricity";
            default:
                return "airtime";
        }
    }

    private String normalizePhoneTo12Digits(String phone) {
        if (phone == null || phone.isEmpty()) {
            throw new RuntimeException("Phone number cannot be null or empty");
        }
        
        // Remove all non-digit characters
        String digitsOnly = phone.replaceAll("[^0-9]", "");
        
        // If starts with +250, remove it and add back
        if (digitsOnly.startsWith("250") && digitsOnly.length() == 12) {
            return digitsOnly;
        }
        
        // If starts with 0, replace with 250
        if (digitsOnly.startsWith("0") && digitsOnly.length() == 10) {
            return "250" + digitsOnly.substring(1);
        }
        
        // If 9 digits, add 250 prefix
        if (digitsOnly.length() == 9) {
            return "250" + digitsOnly;
        }
        
        // If already 12 digits, return as is
        if (digitsOnly.length() == 12) {
            return digitsOnly;
        }
        
        throw new RuntimeException("Invalid phone number format. Expected 9, 10, or 12 digits. Got: " + phone + " (normalized: " + digitsOnly + ")");
    }

    /**
     * Generates a unique transaction ID starting with "EFASHE"
     * Format: EFASHE + timestamp (milliseconds) + random numeric string
     * Example: EFASHE17691234567894562
     */
    private String generateEfasheTransactionId() {
        long timestamp = System.currentTimeMillis();
        // Generate a random numeric string (6 digits) to ensure uniqueness
        java.util.Random random = new java.util.Random();
        int randomNum = random.nextInt(1000000); // 0 to 999999
        // Pad with zeros to ensure 6 digits
        String randomPart = String.format("%06d", randomNum);
        return "EFASHE" + timestamp + randomPart;
    }
    
    /**
     * Send WhatsApp and SMS notifications to the customer when EFASHE transaction is SUCCESS
     */
    private void sendWhatsAppNotification(EfasheTransaction transaction) {
        try {
            logger.info("=== START Payment Notification (WhatsApp + SMS) for EFASHE Transaction ===");
            logger.info("Transaction ID: {}", transaction.getTransactionId());
            logger.info("Customer Phone: {}", transaction.getCustomerPhone());
            logger.info("Amount: {}", transaction.getAmount());
            logger.info("Service Type: {}", transaction.getServiceType());
            logger.info("EFASHE Status: {}", transaction.getEfasheStatus());
            logger.info("Customer Cashback Amount: {}", transaction.getCustomerCashbackAmount());
            
            if (transaction.getCustomerPhone() == null || transaction.getCustomerPhone().isEmpty()) {
                logger.warn("Customer phone number is null or empty, skipping notifications for transaction: {}", 
                    transaction.getTransactionId());
                return;
            }
            
            // Find user by phone number (try multiple formats)
            String normalizedPhone = normalizePhoneTo12Digits(transaction.getCustomerPhone());
            logger.info("Normalized customer phone: {} (original: {})", normalizedPhone, transaction.getCustomerPhone());
            
            Optional<User> userOpt = userRepository.findByPhoneNumber(normalizedPhone);
            
            // If not found, try other formats
            if (!userOpt.isPresent()) {
                String phoneDigitsOnly = transaction.getCustomerPhone().replaceAll("[^0-9]", "");
                userOpt = userRepository.findByPhoneNumber(phoneDigitsOnly);
                logger.debug("Trying phone format without normalization: {}", phoneDigitsOnly);
            }
            
            if (!userOpt.isPresent() && normalizedPhone.startsWith("250") && normalizedPhone.length() == 12) {
                String phoneWithout250 = "0" + normalizedPhone.substring(3);
                userOpt = userRepository.findByPhoneNumber(phoneWithout250);
                logger.debug("Trying phone format with 0 prefix: {}", phoneWithout250);
            }
            
            // Build notification messages
            String serviceName = transaction.getServiceType() != null ? transaction.getServiceType().toString() : "payment";
            String amount = transaction.getAmount() != null ? transaction.getAmount().toPlainString() : "0";
            String cashbackAmount = transaction.getCustomerCashbackAmount() != null 
                ? transaction.getCustomerCashbackAmount().toPlainString() : "0";
            
            // WhatsApp message
            String whatsAppMessage = String.format(
                "You Paid %s, %s and your cash back is %s, Thanks for using POCHI App",
                serviceName,
                amount,
                cashbackAmount
            );
            
            // SMS message (shortened format for SMS)
            String smsMessage = String.format(
                "Paid %s %s RWF. Cashback: %s RWF. Thanks!",
                serviceName,
                amount,
                cashbackAmount
            );
            
            // Use the same phone format as PaymentService (12 digits with 250)
            String notificationPhone = normalizedPhone;
            logger.info("=== Sending Notifications (SMS + WhatsApp) ===");
            logger.info("Phone: {}", notificationPhone);
            logger.info("WhatsApp Message: {}", whatsAppMessage);
            logger.info("SMS Message: {}", smsMessage);
            
            // Send SMS notification
            try {
                messagingService.sendSms(smsMessage, notificationPhone);
                logger.info("✅ SMS notification sent successfully to customer {} for EFASHE transaction: {}", 
                    notificationPhone, transaction.getTransactionId());
            } catch (Exception smsException) {
                logger.error("Failed to send SMS notification for transaction {}: ", transaction.getTransactionId(), smsException);
                // Continue with WhatsApp even if SMS fails
            }
            
            // Send WhatsApp notification
            try {
                whatsAppService.sendWhatsApp(whatsAppMessage, notificationPhone);
                logger.info("✅ WhatsApp notification sent successfully to customer {} for EFASHE transaction: {}", 
                    notificationPhone, transaction.getTransactionId());
            } catch (Exception whatsAppException) {
                logger.error("Failed to send WhatsApp notification for transaction {}: ", transaction.getTransactionId(), whatsAppException);
                // Continue even if WhatsApp fails
            }
            
            logger.info("=== END Payment Notification (SMS + WhatsApp) ===");
        } catch (Exception e) {
            logger.error("=== ERROR in Payment Notification ===");
            logger.error("Transaction ID: {}", transaction.getTransactionId());
            logger.error("Customer Phone: {}", transaction.getCustomerPhone());
            logger.error("Error sending notifications for EFASHE transaction {}: ", 
                transaction.getTransactionId(), e);
            e.printStackTrace();
            logger.error("=== END Payment Notification Error ===");
            // Don't fail the transaction if notifications fail
        }
    }
    
    /**
     * Format electricity token with dashes (e.g., 34941584149888503133 -> 3494-5841-4988-8503-133)
     */
    private String formatElectricityToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return token;
        }
        
        // Remove any existing dashes or spaces
        String cleanToken = token.replaceAll("[^0-9]", "");
        
        // Format with dashes every 4 digits
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < cleanToken.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                formatted.append("-");
            }
            formatted.append(cleanToken.charAt(i));
        }
        
        return formatted.toString();
    }

    /**
     * Send electricity token to user via SMS (Swiftcom or BeSoft)
     * Only called for ELECTRICITY service type after successful execution
     */
    private void sendElectricityToken(EfasheTransaction transaction, String token, ElectricityTokenResponse.TokenData tokenData) {
        try {
            logger.info("=== START Electricity Token Notification ===");
            logger.info("Transaction ID: {}", transaction.getTransactionId());
            logger.info("Customer Phone: {}", transaction.getCustomerPhone());
            logger.info("Meter Number: {}", transaction.getCustomerAccountNumber());
            logger.info("Token (raw): {}", token);
            logger.info("Units: {}", tokenData.getUnits());
            
            if (transaction.getCustomerPhone() == null || transaction.getCustomerPhone().isEmpty()) {
                logger.warn("Customer phone number is null or empty, skipping token notification for transaction: {}", 
                    transaction.getTransactionId());
                return;
            }
            
            // Normalize phone number
            String normalizedPhone = normalizePhoneTo12Digits(transaction.getCustomerPhone());
            logger.info("Normalized customer phone for token notification: {} (original: {})", normalizedPhone, transaction.getCustomerPhone());
            
            // Format token with dashes
            String formattedToken = formatElectricityToken(token);
            logger.info("Formatted token: {} (original: {})", formattedToken, token);
            
            // Build token message
            // Format units to one decimal place
            String units = "N/A";
            if (tokenData.getUnits() != null) {
                BigDecimal unitsValue = tokenData.getUnits().setScale(1, RoundingMode.HALF_UP);
                units = unitsValue.toPlainString();
            }
            String amount = transaction.getAmount() != null ? transaction.getAmount().toPlainString() : "0";
            String meterNo = transaction.getCustomerAccountNumber() != null ? transaction.getCustomerAccountNumber() : "N/A";
            
            String tokenMessage = String.format(
                "Electricity token for meter %s:\nToken: %s\nUnits: %s kWh\nAmount: %s RWF\nThanks!",
                meterNo,
                formattedToken,
                units,
                amount
            );
            
            // Send via SMS
            logger.info("Sending electricity token via SMS to: {}", normalizedPhone);
            try {
                messagingService.sendSms(tokenMessage, normalizedPhone);
                logger.info("✅ Electricity token sent successfully via SMS");
            } catch (Exception smsException) {
                logger.error("Failed to send electricity token via SMS: ", smsException);
                // Continue with WhatsApp even if SMS fails
            }
            
            // Send via WhatsApp
            logger.info("Sending electricity token via WhatsApp to: {}", normalizedPhone);
            try {
                whatsAppService.sendWhatsApp(tokenMessage, normalizedPhone);
                logger.info("✅ Electricity token sent successfully via WhatsApp");
            } catch (Exception whatsAppException) {
                logger.error("Failed to send electricity token via WhatsApp: ", whatsAppException);
                // Continue even if WhatsApp fails
            }
            
            logger.info("=== END Electricity Token Notification ===");
        } catch (Exception e) {
            logger.error("=== ERROR in Electricity Token Notification ===");
            logger.error("Transaction ID: {}", transaction.getTransactionId());
            logger.error("Customer Phone: {}", transaction.getCustomerPhone());
            logger.error("Error sending electricity token notification: ", e);
            logger.error("=== END Electricity Token Error ===");
            // Don't fail the transaction if token notification fails
        }
    }
    
    /**
     * Get EFASHE transactions with optional filtering by service type, phone number, and date range
     * - ADMIN users can see all transactions (can optionally filter by phone)
     * - RECEIVER/MERCHANT users can see all transactions (can optionally filter by phone)
     * - USER users can only see their own transactions (automatically filtered by their phone number)
     * @param serviceType Optional service type filter (AIRTIME, RRA, TV, MTN, ELECTRICITY)
     * @param phone Optional phone number filter (will be normalized to 12 digits with 250 prefix)
     *              - For ADMIN/RECEIVER: optional filter
     *              - For USER: ignored, automatically uses their own phone number
     * @param page Page number (0-indexed)
     * @param size Page size
     * @param fromDate Optional start date filter
     * @param toDate Optional end date filter
     * @return Paginated response with EFASHE transactions
     */
    @Transactional(readOnly = true)
    public PaginatedResponse<EfasheTransactionResponse> getTransactions(
            EfasheServiceType serviceType,
            String phone,
            int page,
            int size,
            LocalDateTime fromDate,
            LocalDateTime toDate) {
        
        // Get current authentication to check user role
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = false;
        boolean isReceiver = false;
        String userPhone = null;
        
        if (authentication != null && authentication.isAuthenticated()) {
            // Check if user has ADMIN role
            isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.equals("ROLE_ADMIN"));
            
            // Check if user has RECEIVER role
            isReceiver = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.equals("ROLE_RECEIVER"));
            
            // For USER tokens, the subject is the phone number
            if (!isAdmin && !isReceiver) {
                userPhone = authentication.getName();
                logger.info("USER detected, will filter by user's phone number: {}", userPhone);
            } else if (isAdmin) {
                logger.info("Admin user detected, can see all transactions");
            } else if (isReceiver) {
                logger.info("Receiver/Merchant user detected, can see all transactions");
            }
        }
        
        // Normalize phone number for filtering
        String normalizedPhone = null;
        
        if (isAdmin || isReceiver) {
            // ADMIN and RECEIVER: Use provided phone filter if any
            if (phone != null && !phone.trim().isEmpty()) {
                try {
                    normalizedPhone = normalizePhoneTo12Digits(phone);
                    logger.info("{} filtering by phone: {} -> {}", isAdmin ? "Admin" : "Merchant", phone, normalizedPhone);
                } catch (Exception e) {
                    logger.warn("Invalid phone number format for filtering: {}, error: {}", phone, e.getMessage());
                    throw new RuntimeException("Invalid phone number format: " + phone);
                }
            }
        } else {
            // USER: Always filter by their own phone number
            if (userPhone != null && !userPhone.trim().isEmpty()) {
                try {
                    normalizedPhone = normalizePhoneTo12Digits(userPhone);
                    logger.info("User filtering by own phone number: {} -> {}", userPhone, normalizedPhone);
                } catch (Exception e) {
                    logger.warn("Invalid user phone number format: {}, error: {}", userPhone, e.getMessage());
                    throw new RuntimeException("Unable to determine user phone number for filtering");
                }
            } else {
                throw new RuntimeException("User phone number not found in authentication token");
            }
        }
        
        logger.info("Fetching EFASHE transactions - IsAdmin: {}, IsReceiver: {}, ServiceType: {}, Phone: {} (normalized: {}), Page: {}, Size: {}, FromDate: {}, ToDate: {}", 
            isAdmin, isReceiver, serviceType, phone, normalizedPhone, page, size, fromDate, toDate);
        
        // Build dynamic query to avoid PostgreSQL type inference issues with nullable parameters
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT t FROM EfasheTransaction t WHERE 1=1 ");
        
        // Add service type filter if provided
        if (serviceType != null) {
            queryBuilder.append("AND t.serviceType = :serviceType ");
        }
        
        // Add phone filter if provided
        if (normalizedPhone != null && !normalizedPhone.trim().isEmpty()) {
            queryBuilder.append("AND t.customerPhone = :customerPhone ");
        }
        
        // Add date range filters if provided
        if (fromDate != null) {
            queryBuilder.append("AND t.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            queryBuilder.append("AND t.createdAt <= :toDate ");
        }
        
        queryBuilder.append("ORDER BY t.createdAt DESC");
        
        // Create query
        Query query = entityManager.createQuery(queryBuilder.toString(), EfasheTransaction.class);
        
        // Set parameters only if they are not null
        if (serviceType != null) {
            query.setParameter("serviceType", serviceType);
        }
        if (normalizedPhone != null && !normalizedPhone.trim().isEmpty()) {
            query.setParameter("customerPhone", normalizedPhone);
        }
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        
        // Build count query for pagination
        StringBuilder countQueryBuilder = new StringBuilder();
        countQueryBuilder.append("SELECT COUNT(t) FROM EfasheTransaction t WHERE 1=1 ");
        
        if (serviceType != null) {
            countQueryBuilder.append("AND t.serviceType = :serviceType ");
        }
        if (normalizedPhone != null && !normalizedPhone.trim().isEmpty()) {
            countQueryBuilder.append("AND t.customerPhone = :customerPhone ");
        }
        if (fromDate != null) {
            countQueryBuilder.append("AND t.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            countQueryBuilder.append("AND t.createdAt <= :toDate ");
        }
        
        Query countQuery = entityManager.createQuery(countQueryBuilder.toString(), Long.class);
        
        // Set count query parameters
        if (serviceType != null) {
            countQuery.setParameter("serviceType", serviceType);
        }
        if (normalizedPhone != null && !normalizedPhone.trim().isEmpty()) {
            countQuery.setParameter("customerPhone", normalizedPhone);
        }
        if (fromDate != null) {
            countQuery.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            countQuery.setParameter("toDate", toDate);
        }
        
        // Get total count
        long totalElements = (Long) countQuery.getSingleResult();
        
        // Apply pagination
        int offset = page * size;
        query.setFirstResult(offset);
        query.setMaxResults(size);
        
        @SuppressWarnings("unchecked")
        List<EfasheTransaction> transactions = (List<EfasheTransaction>) query.getResultList();
        
        // Convert to response DTOs
        List<EfasheTransactionResponse> responses = transactions.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
        
        // Calculate pagination metadata
        int totalPages = (int) Math.ceil((double) totalElements / size);
        
        PaginatedResponse<EfasheTransactionResponse> paginatedResponse = new PaginatedResponse<>();
        paginatedResponse.setContent(responses);
        paginatedResponse.setTotalElements(totalElements);
        paginatedResponse.setTotalPages(totalPages);
        paginatedResponse.setCurrentPage(page);
        paginatedResponse.setPageSize(size);
        paginatedResponse.setFirst(page == 0);
        paginatedResponse.setLast(page >= totalPages - 1);
        
        logger.info("Retrieved {} EFASHE transactions (total: {})", responses.size(), totalElements);
        return paginatedResponse;
    }
    
    /**
     * Map EfasheTransaction entity to EfasheTransactionResponse DTO
     */
    private EfasheTransactionResponse mapToResponse(EfasheTransaction transaction) {
        EfasheTransactionResponse response = new EfasheTransactionResponse();
        response.setId(transaction.getId());
        response.setTransactionId(transaction.getTransactionId());
        response.setServiceType(transaction.getServiceType());
        response.setCustomerPhone(transaction.getCustomerPhone());
        response.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
        response.setAmount(transaction.getAmount());
        response.setCurrency(transaction.getCurrency());
        response.setTrxId(transaction.getTrxId());
        response.setMopayTransactionId(transaction.getMopayTransactionId());
        response.setMopayStatus(transaction.getMopayStatus());
        response.setEfasheStatus(transaction.getEfasheStatus());
        response.setDeliveryMethodId(transaction.getDeliveryMethodId());
        response.setDeliverTo(transaction.getDeliverTo());
        response.setPollEndpoint(transaction.getPollEndpoint());
        response.setRetryAfterSecs(transaction.getRetryAfterSecs());
        response.setMessage(transaction.getMessage());
        response.setErrorMessage(transaction.getErrorMessage());
        response.setCustomerCashbackAmount(transaction.getCustomerCashbackAmount());
        response.setBesoftShareAmount(transaction.getBesoftShareAmount());
        response.setFullAmountPhone(transaction.getFullAmountPhone());
        response.setCashbackPhone(transaction.getCashbackPhone());
        response.setCashbackSent(transaction.getCashbackSent());
        response.setCreatedAt(transaction.getCreatedAt());
        response.setUpdatedAt(transaction.getUpdatedAt());
        return response;
    }
}

