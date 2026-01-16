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
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    public EfashePaymentService(EfasheSettingsService efasheSettingsService, 
                                 MoPayService moPayService,
                                 EfasheApiService efasheApiService,
                                 EfasheTransactionRepository efasheTransactionRepository,
                                 WhatsAppService whatsAppService,
                                 UserRepository userRepository,
                                 EntityManager entityManager) {
        this.efasheSettingsService = efasheSettingsService;
        this.moPayService = moPayService;
        this.efasheApiService = efasheApiService;
        this.efasheTransactionRepository = efasheTransactionRepository;
        this.whatsAppService = whatsAppService;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
    }

    public EfasheInitiateResponse initiatePayment(EfasheInitiateRequest request) {
        logger.info("Initiating EFASHE payment - Service: {}, Amount: {}, Phone: {}", 
            request.getServiceType(), request.getAmount(), request.getPhone());

        // Get EFASHE settings for the service type
        EfasheSettingsResponse settingsResponse = efasheSettingsService.getSettingsByServiceType(request.getServiceType());

        BigDecimal amount = request.getAmount();
        
        // Normalize customer phone first (needed for account number determination)
        String normalizedCustomerPhone = normalizePhoneTo12Digits(request.getPhone());
        
        // Determine customer account number BEFORE validate
        // For AIRTIME: use phone as account number (convert to account format)
        // For other services (RRA, TV, ELECTRICITY): use customerAccountNumber from request
        String customerAccountNumber;
        if (request.getServiceType() == EfasheServiceType.AIRTIME || request.getServiceType() == EfasheServiceType.MTN) {
            // Convert customer phone to account number format for EFASHE
            // Remove 250 prefix and add 0 prefix: 250784638201 -> 0784638201
            customerAccountNumber = normalizedCustomerPhone.startsWith("250") 
                ? "0" + normalizedCustomerPhone.substring(3) 
                : (normalizedCustomerPhone.startsWith("0") ? normalizedCustomerPhone : "0" + normalizedCustomerPhone);
            logger.info("AIRTIME/MTN service - Using phone as account number: {} (from phone: {})", customerAccountNumber, normalizedCustomerPhone);
        } else {
            // For RRA, TV, ELECTRICITY: use customerAccountNumber from request
            if (request.getCustomerAccountNumber() == null || request.getCustomerAccountNumber().trim().isEmpty()) {
                throw new RuntimeException("customerAccountNumber is required for service type: " + request.getServiceType());
            }
            customerAccountNumber = request.getCustomerAccountNumber().trim();
            logger.info("Non-AIRTIME service ({}) - Using customerAccountNumber from request: {} (phone for MoPay: {})", 
                request.getServiceType(), customerAccountNumber, normalizedCustomerPhone);
        }

        // ===================================================================
        // STEP 1: Validate with EFASHE BEFORE initiating MoPay
        // ===================================================================
        logger.info("=== STEP 1: Validating with EFASHE BEFORE MoPay initiation ===");
        EfasheValidateRequest validateRequest = new EfasheValidateRequest();
        validateRequest.setVerticalId(getVerticalId(request.getServiceType()));
        validateRequest.setCustomerAccountNumber(customerAccountNumber);
        
        logger.info("Calling EFASHE validate - Vertical: {}, Customer Account Number: {}", 
            validateRequest.getVerticalId(), validateRequest.getCustomerAccountNumber());
        
        EfasheValidateResponse validateResponse = efasheApiService.validateAccount(validateRequest);
        
        // Validate MUST succeed before proceeding with MoPay
        if (validateResponse == null || validateResponse.getTrxId() == null || validateResponse.getTrxId().trim().isEmpty()) {
            String errorMsg = "EFASHE validation failed - Invalid account or service unavailable";
            if (validateResponse != null && validateResponse.getTrxResult() != null) {
                errorMsg = "EFASHE validation failed: " + validateResponse.getTrxResult();
            }
            logger.error("EFASHE validation failed - Cannot proceed with MoPay. Account: {}, Service: {}", 
                customerAccountNumber, request.getServiceType());
            throw new RuntimeException(errorMsg);
        }
        
        logger.info("EFASHE validation successful - TrxId: {}, Account: {}", 
            validateResponse.getTrxId(), customerAccountNumber);
        
        // Store customer account name if available
        String customerAccountName = null;
        if (validateResponse.getCustomerAccountName() != null && !validateResponse.getCustomerAccountName().trim().isEmpty()) {
            customerAccountName = validateResponse.getCustomerAccountName().trim();
            logger.info("Customer Account Name from validate: {}", customerAccountName);
        }
        
        // Determine delivery method from validate response
        String deliveryMethodId = "direct_topup"; // Default
        if (validateResponse.getDeliveryMethods() != null && !validateResponse.getDeliveryMethods().isEmpty()) {
            if (request.getServiceType() == EfasheServiceType.ELECTRICITY || 
                request.getServiceType() == EfasheServiceType.RRA) {
                // For ELECTRICITY and RRA, prefer SMS delivery method
                boolean hasSms = validateResponse.getDeliveryMethods().stream()
                    .anyMatch(dm -> "sms".equals(dm.getId()));
                
                if (hasSms) {
                    deliveryMethodId = "sms";
                    logger.info("{} service - Using SMS delivery method (preferred for this service type)", request.getServiceType());
                } else {
                    // Use the first available delivery method
                    deliveryMethodId = validateResponse.getDeliveryMethods().get(0).getId();
                    logger.info("{} service - SMS not available, using delivery method: {}", request.getServiceType(), deliveryMethodId);
                }
            } else {
                // For other services (AIRTIME, TV, MTN), prefer direct_topup
                boolean hasDirectTopup = validateResponse.getDeliveryMethods().stream()
                    .anyMatch(dm -> "direct_topup".equals(dm.getId()));
                
                if (hasDirectTopup) {
                    deliveryMethodId = "direct_topup";
                } else {
                    // Use the first available delivery method
                    deliveryMethodId = validateResponse.getDeliveryMethods().get(0).getId();
                }
            }
        }
        
        logger.info("=== EFASHE Validation Complete - Proceeding with MoPay Initiation ===");

        // ===================================================================
        // STEP 2: Proceed with MoPay initiation (validate passed)
        // ===================================================================
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

        // No need to generate transaction IDs - MoPay will generate its own unique IDs
        // We'll use MoPay's transaction ID as the primary identifier

        logger.info("Amount breakdown - Total: {}, Customer Cashback: {}, Besoft Share: {}, Agent Commission: {}, Full Amount Phone (remaining after cashback and besoft): {}",
            amount, customerCashbackAmount, besoftShareAmount, agentCommissionAmount, fullAmountPhoneReceives);

        // Validate that fullAmountPhoneReceives is not negative
        if (fullAmountPhoneReceives.compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("Invalid percentage configuration: Total percentages exceed 100%. Full amount phone would receive negative amount: " + fullAmountPhoneReceives);
        }

        // Build MoPay request - DON'T set transaction_id, let MoPay generate its own (like PaymentService does)
        // This prevents MoPay from rejecting duplicate transaction IDs
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        // NOT setting transaction_id - MoPay will generate its own unique ID
        logger.info("Not setting transaction_id in MoPay request - MoPay will generate its own unique transaction ID");
        moPayRequest.setAmount(amount);
        moPayRequest.setCurrency(request.getCurrency());
        
        // MoPay API requires phone as Long, so convert for API call but keep as String in DTO
        moPayRequest.setPhone(normalizedCustomerPhone); // Will be converted to Long in MoPayService if needed
        moPayRequest.setPayment_mode(request.getPayment_mode());
        moPayRequest.setMessage(request.getMessage() != null ? request.getMessage() : 
            "EFASHE " + request.getServiceType() + " payment");
        moPayRequest.setCallback_url(request.getCallback_url());

        // Build transfers array - Include ALL transfers (Full Amount, Customer Cashback, Besoft Share) in the same request
        // Don't set transaction_id on transfers either - let MoPay handle it
        List<MoPayInitiateRequest.Transfer> transfers = new ArrayList<>();

        // Transfer 1: Full Amount Phone Number - receives amount minus customer cashback and besoft share
        MoPayInitiateRequest.Transfer transfer1 = new MoPayInitiateRequest.Transfer();
        // NOT setting transaction_id - MoPay will handle it
        transfer1.setAmount(fullAmountPhoneReceives);
        String normalizedFullAmountPhone = normalizePhoneTo12Digits(settingsResponse.getFullAmountPhoneNumber());
        transfer1.setPhone(Long.parseLong(normalizedFullAmountPhone)); // Phone as number (Long)
        transfer1.setMessage("EFASHE " + request.getServiceType() + " - Full amount");
        transfers.add(transfer1);
        logger.info("Transfer 1 - Full Amount Phone: {}, Amount: {}", 
            normalizedFullAmountPhone, fullAmountPhoneReceives);
        
        // Transfer 2: Customer Cashback - send customer cashback back to customer
        if (customerCashbackAmount.compareTo(BigDecimal.ZERO) > 0) {
            MoPayInitiateRequest.Transfer transfer2 = new MoPayInitiateRequest.Transfer();
            // NOT setting transaction_id - MoPay will handle it
            transfer2.setAmount(customerCashbackAmount);
            transfer2.setPhone(Long.parseLong(normalizedCustomerPhone)); // Customer phone
            transfer2.setMessage("EFASHE " + request.getServiceType() + " - Customer cashback");
            transfers.add(transfer2);
            logger.info("Transfer 2 - Customer Cashback: {}, Amount: {}", 
                normalizedCustomerPhone, customerCashbackAmount);
        }
        
        // Transfer 3: Besoft Share - send besoft share to besoft phone
        if (besoftShareAmount.compareTo(BigDecimal.ZERO) > 0) {
            String normalizedCashbackPhone = normalizePhoneTo12Digits(settingsResponse.getCashbackPhoneNumber());
            MoPayInitiateRequest.Transfer transfer3 = new MoPayInitiateRequest.Transfer();
            // NOT setting transaction_id - MoPay will handle it
            transfer3.setAmount(besoftShareAmount);
            transfer3.setPhone(Long.parseLong(normalizedCashbackPhone)); // Besoft phone
            transfer3.setMessage("EFASHE " + request.getServiceType() + " - Besoft share");
            transfers.add(transfer3);
            logger.info("Transfer 3 - Besoft Share: {}, Amount: {}", 
                normalizedCashbackPhone, besoftShareAmount);
        }
        
        logger.info("All transfers included in initial MoPay request - Total transfers: {}. MoPay will generate transaction IDs.", 
            transfers.size());

        moPayRequest.setTransfers(transfers);

        // Initiate payment with MoPay - no need to set transaction_id, MoPay will generate its own
        // This matches PaymentService behavior and prevents duplicate ID rejections
        MoPayResponse moPayResponse = moPayService.initiatePayment(moPayRequest);
        
        // Store transaction record in database for later status checking
        // ALWAYS create a NEW transaction record for each initiate request
        // Use MoPay's transaction ID as the primary identifier
        String mopayTransactionId = moPayResponse != null && moPayResponse.getTransactionId() != null 
            ? moPayResponse.getTransactionId() : null;
        
        if (mopayTransactionId == null || mopayTransactionId.isEmpty()) {
            throw new RuntimeException("MoPay did not return a transaction ID. Payment initiation may have failed.");
        }
        
        EfasheTransaction transaction = new EfasheTransaction();
        transaction.setTransactionId(mopayTransactionId); // Use MoPay transaction ID as primary identifier
        transaction.setServiceType(request.getServiceType());
        transaction.setCustomerPhone(normalizedCustomerPhone);
        transaction.setCustomerAccountNumber(customerAccountNumber);
        
        // Store validate response data
        transaction.setTrxId(validateResponse.getTrxId());
        if (customerAccountName != null) {
            transaction.setCustomerAccountName(customerAccountName);
        }
        transaction.setDeliveryMethodId(deliveryMethodId);
        transaction.setAmount(amount);
        transaction.setCurrency(request.getCurrency());
        transaction.setMopayTransactionId(mopayTransactionId); // Also store in mopayTransactionId field
        
        // Set INITIAL STATUS - this is the status when transaction was first created
        String initialMopayStatus = moPayResponse != null && moPayResponse.getStatus() != null 
            ? moPayResponse.getStatus().toString() : "PENDING";
        transaction.setInitialMopayStatus(initialMopayStatus); // Store initial status (never changes)
        transaction.setInitialEfasheStatus("PENDING"); // Store initial EFASHE status (never changes)
        
        // Set CURRENT STATUS - this will be updated when checking status later
        transaction.setMopayStatus(initialMopayStatus); // Current status (will be updated on status check)
        transaction.setEfasheStatus("PENDING"); // Current status (will be updated on status check)
        
        transaction.setMessage(moPayRequest.getMessage());
        
        // For ELECTRICITY and RRA services, if delivery method is SMS, set deliverTo to customer phone
        // This will be used when we execute the EFASHE transaction
        if ("sms".equals(deliveryMethodId)) {
            transaction.setDeliverTo(normalizedCustomerPhone); // Customer phone for SMS delivery
            logger.info("{} service - Delivery method is SMS, setting deliverTo to customer phone: {}", 
                request.getServiceType(), normalizedCustomerPhone);
        }
        
        // Store cashback amounts and phone numbers for reference
        transaction.setCustomerCashbackAmount(customerCashbackAmount);
        transaction.setBesoftShareAmount(besoftShareAmount);
        transaction.setFullAmountPhone(normalizePhoneTo12Digits(settingsResponse.getFullAmountPhoneNumber()));
        transaction.setCashbackPhone(normalizePhoneTo12Digits(settingsResponse.getCashbackPhoneNumber()));
        transaction.setCashbackSent(true); // Mark as sent since transfers are included in initial request
        
        // No need to store separate transfer transaction IDs - MoPay handles this
        
        // Save transaction - creates a NEW row in database for each initiation
        efasheTransactionRepository.save(transaction);
        logger.info("Saved EFASHE transaction record - MoPay Transaction ID: {}", mopayTransactionId);

        // Build response - normalize all phone numbers to consistent format
        EfasheInitiateResponse response = new EfasheInitiateResponse();
        response.setTransactionId(mopayTransactionId); // Use MoPay transaction ID
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

        logger.info("EFASHE payment initiated successfully - MoPay Transaction ID: {}", mopayTransactionId);

        return response;
    }

    /**
     * Check the status of an EFASHE transaction using MoPay transaction ID
     * If status is SUCCESS (200), automatically triggers EFASHE validate and execute
     * @param transactionId The MoPay transaction ID (used as primary identifier)
     * @return EfasheStatusResponse containing the transaction status and EFASHE responses
     */
    @Transactional
    public EfasheStatusResponse checkTransactionStatus(String transactionId) {
        logger.info("Checking EFASHE transaction status for MoPay transaction ID: {}", transactionId);
        
        // Get stored transaction record - ALWAYS update existing row, never create new one
        // transactionId is now the MoPay transaction ID
        EfasheTransaction transaction = efasheTransactionRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new RuntimeException("EFASHE transaction not found: " + transactionId));
        
        logger.info("Found existing transaction - ID: {}, Current MoPay Status: {}, Current EFASHE Status: {}, Initial MoPay Status: {}, Initial EFASHE Status: {}", 
            transaction.getId(), transaction.getMopayStatus(), transaction.getEfasheStatus(), 
            transaction.getInitialMopayStatus(), transaction.getInitialEfasheStatus());
        
        // Check MoPay status using the transaction ID (which is the MoPay transaction ID)
        MoPayResponse moPayResponse = moPayService.checkTransactionStatus(transactionId);
        
        EfasheExecuteResponse executeResponse = null;
        
        // Update MoPay status in transaction record (UPDATE existing row, don't create new)
        if (moPayResponse != null && moPayResponse.getStatus() != null) {
            Integer statusCode = moPayResponse.getStatus();
            // Only update current status, keep initial status unchanged
            transaction.setMopayStatus(statusCode.toString());
            transaction.setMopayTransactionId(moPayResponse.getTransactionId());
            
            // Ensure initial status is set if it wasn't set before (for backward compatibility)
            if (transaction.getInitialMopayStatus() == null) {
                transaction.setInitialMopayStatus(transaction.getMopayStatus());
            }
            if (transaction.getInitialEfasheStatus() == null) {
                transaction.setInitialEfasheStatus(transaction.getEfasheStatus() != null ? transaction.getEfasheStatus() : "PENDING");
            }
            
            logger.info("MoPay status check - Status: {}, Success: {}, Transaction ID: {}", 
                statusCode, moPayResponse.getSuccess(), moPayResponse.getTransactionId());
            
            // If MoPay status is SUCCESS (200 or 201) OR success flag is true, trigger EFASHE execute
            boolean isSuccess = (statusCode != null && (statusCode == 200 || statusCode == 201)) 
                || (moPayResponse.getSuccess() != null && moPayResponse.getSuccess());
            
            if (isSuccess && !"SUCCESS".equalsIgnoreCase(transaction.getEfasheStatus())) {
                logger.info("MoPay transaction SUCCESS detected (status: {}, success: {}). Triggering EFASHE execute...", 
                    statusCode, moPayResponse.getSuccess());
                
                try {
                    // Validation was already done during initiate, so we use the stored trxId
                    String trxId = transaction.getTrxId();
                    if (trxId == null || trxId.trim().isEmpty()) {
                        // This shouldn't happen if validation was done correctly, but handle gracefully
                        logger.error("Transaction missing trxId from validate - Cannot execute EFASHE transaction. Transaction ID: {}", 
                            transaction.getTransactionId());
                        transaction.setEfasheStatus("FAILED");
                        transaction.setErrorMessage("Missing trxId - validation may not have been completed during initiate");
                        efasheTransactionRepository.save(transaction);
                    }
                    
                    logger.info("Using stored trxId from validate (done during initiate) - TrxId: {}", trxId);
                    
                    // Execute EFASHE transaction using stored trxId and delivery method info
                    EfasheExecuteRequest executeRequest = new EfasheExecuteRequest();
                    executeRequest.setTrxId(trxId);
                    executeRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                    executeRequest.setAmount(transaction.getAmount());
                    executeRequest.setVerticalId(getVerticalId(transaction.getServiceType()));
                    
                    // Use stored delivery method and deliverTo (already determined during initiate)
                    String deliveryMethodId = transaction.getDeliveryMethodId();
                    if (deliveryMethodId == null || deliveryMethodId.trim().isEmpty()) {
                        deliveryMethodId = "direct_topup"; // Default fallback
                        logger.warn("No delivery method stored, using default: {}", deliveryMethodId);
                    }
                    executeRequest.setDeliveryMethodId(deliveryMethodId);
                    
                    // If delivery method is "sms", use stored deliverTo
                    if ("sms".equals(deliveryMethodId)) {
                        String deliverTo = transaction.getDeliverTo();
                        if (deliverTo != null && !deliverTo.trim().isEmpty()) {
                            executeRequest.setDeliverTo(deliverTo);
                            logger.info("Using stored deliverTo for SMS delivery: {}", deliverTo);
                        } else {
                            // Fallback: use customer phone
                            String customerPhone = normalizePhoneTo12Digits(transaction.getCustomerPhone());
                            executeRequest.setDeliverTo(customerPhone);
                            transaction.setDeliverTo(customerPhone);
                            logger.info("No stored deliverTo, using customer phone: {}", customerPhone);
                        }
                    }
                    // deliverTo and callBack are optional for direct_topup
                    
                    logger.info("Calling EFASHE execute - TrxId: {}, Amount: {}, Account: {}, DeliveryMethod: {}", 
                        executeRequest.getTrxId(), executeRequest.getAmount(), executeRequest.getCustomerAccountNumber(), 
                        executeRequest.getDeliveryMethodId());
                    executeResponse = efasheApiService.executeTransaction(executeRequest);
                        
                        if (executeResponse != null) {
                            // EFASHE execute returns async response with pollEndpoint
                            transaction.setPollEndpoint(executeResponse.getPollEndpoint());
                            transaction.setRetryAfterSecs(executeResponse.getRetryAfterSecs());
                            
                            // For ELECTRICITY service, extract and store token information if available
                            if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                                String token = executeResponse.getToken();
                                if (token != null && !token.isEmpty()) {
                                    // Store token in message field (since we don't have a separate token field)
                                    // Token will be included in WhatsApp message
                                    String existingMessage = executeResponse.getMessage() != null ? executeResponse.getMessage() : "";
                                    String messageWithToken = existingMessage + (existingMessage.isEmpty() ? "" : " | ") + "Token: " + token;
                                    transaction.setMessage(messageWithToken);
                                    logger.info("ELECTRICITY service - Token received: {}", token);
                                } else {
                                    // Check if token is in message
                                    if (executeResponse.getMessage() != null && executeResponse.getMessage().contains("token")) {
                                        logger.info("ELECTRICITY service - Token information may be in message: {}", executeResponse.getMessage());
                                    }
                                    transaction.setMessage(executeResponse.getMessage());
                                }
                            }
                            
                            // If /vend/execute returns HTTP 200 or 202, set status to SUCCESS immediately
                            // HTTP 200/202 means the execute request was successful, regardless of pollEndpoint
                            // Skip polling - treat as SUCCESS immediately
                            if (executeResponse.getHttpStatusCode() != null && 
                                (executeResponse.getHttpStatusCode() == 200 || executeResponse.getHttpStatusCode() == 202)) {
                                // Only override message if not already set for ELECTRICITY
                                if (transaction.getServiceType() != EfasheServiceType.ELECTRICITY || transaction.getMessage() == null) {
                                    transaction.setEfasheStatus("SUCCESS");
                                    transaction.setMessage(executeResponse.getMessage() != null ? executeResponse.getMessage() : 
                                        "EFASHE transaction executed successfully (HTTP " + executeResponse.getHttpStatusCode() + ")");
                                } else {
                                    transaction.setEfasheStatus("SUCCESS");
                                }
                                logger.info("EFASHE execute returned HTTP {} - Setting status to SUCCESS immediately. Skipping polling. PollEndpoint: {}, RetryAfterSecs: {}", 
                                    executeResponse.getHttpStatusCode(), executeResponse.getPollEndpoint(), executeResponse.getRetryAfterSecs());
                                
                                // Update existing transaction (never create new row)
                                efasheTransactionRepository.save(transaction);
                                logger.info("Updated existing transaction row - Transaction ID: {}, EFASHE Status: SUCCESS", transaction.getTransactionId());
                                
                                // Cashback transfers are already included in the initial MoPay request
                                // No need to send separate cashback transfer requests
                                logger.info("Cashback transfers already included in initial MoPay request - no separate requests needed");
                                
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
                                            
                                            // For ELECTRICITY service, extract and store token information if available
                                            if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                                                String token = pollResponse.getToken();
                                                String pollMessage = pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction completed successfully";
                                                
                                                if (token != null && !token.isEmpty()) {
                                                    // Store token in message field
                                                    String messageWithToken = pollMessage + " | Token: " + token;
                                                    transaction.setMessage(messageWithToken);
                                                    logger.info("ELECTRICITY service - Token received from poll: {}", token);
                                                } else {
                                                    // Check if token is in message
                                                    if (pollMessage != null && pollMessage.toLowerCase().contains("token")) {
                                                        logger.info("ELECTRICITY service - Token information may be in poll message: {}", pollMessage);
                                                        transaction.setMessage(pollMessage);
                                                    } else {
                                                        transaction.setMessage(pollMessage);
                                                    }
                                                }
                                            } else {
                                                transaction.setMessage(pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction completed successfully");
                                            }
                                            
                                            logger.info("EFASHE transaction SUCCESS confirmed from poll - Status: '{}'", trimmedStatus);
                                            
                                            // Update existing transaction (never create new row)
                                            efasheTransactionRepository.save(transaction);
                                            logger.info("Updated existing transaction row - Transaction ID: {}, EFASHE Status: SUCCESS (from poll)", transaction.getTransactionId());
                                            
                                            // Cashback transfers already included in initial MoPay request - no separate requests needed
                                            logger.info("Cashback transfers already included in initial MoPay request - no separate requests needed");
                                            
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
                                
                                // Only send WhatsApp notification if status is SUCCESS
                                // Cashback transfers already included in initial MoPay request
                                if ("SUCCESS".equalsIgnoreCase(efasheStatus)) {
                                    logger.info("EFASHE transaction SUCCESS confirmed - cashback transfers already included in initial MoPay request");
                                    
                                    // Send WhatsApp notification
                                    sendWhatsAppNotification(transaction);
                                } else {
                                    logger.info("EFASHE transaction status is not SUCCESS: {}, cashback transfers will not be sent", efasheStatus);
                                }
                            }
                        } else {
                            transaction.setEfasheStatus("FAILED");
                            transaction.setErrorMessage("EFASHE execute returned null response");
                            logger.error("EFASHE execute returned null response");
                        }
                        // Execute response handling continues below (no validate error handling needed since validate is done in initiate)
                } catch (Exception e) {
                    transaction.setEfasheStatus("FAILED");
                    transaction.setErrorMessage("Failed to execute EFASHE transaction: " + e.getMessage());
                    logger.error("Error executing EFASHE transaction after MoPay SUCCESS: ", e);
                    e.printStackTrace();
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
                                
                                // Update existing transaction (never create new row)
                                efasheTransactionRepository.save(transaction);
                                logger.info("Updated existing transaction row - Transaction ID: {}, EFASHE Status: SUCCESS (from poll)", transaction.getTransactionId());
                                
                                // Cashback transfers already included in initial MoPay request - no separate requests needed
                                logger.info("Cashback transfers already included in initial MoPay request - no separate requests needed");
                                
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
            
            // Update existing transaction (never create new row)
            efasheTransactionRepository.save(transaction);
            logger.info("Updated existing transaction row - Transaction ID: {}, MoPay Status: {} (Initial: {}), EFASHE Status: {} (Initial: {}), Cashback Sent: {}", 
                transaction.getTransactionId(), transaction.getMopayStatus(), transaction.getInitialMopayStatus(),
                transaction.getEfasheStatus(), transaction.getInitialEfasheStatus(), transaction.getCashbackSent());
        }
        
        // Build response with all information
        EfasheStatusResponse response = new EfasheStatusResponse();
        response.setMoPayResponse(moPayResponse);
        response.setValidateResponse(null); // Validate was done in initiate, not in status check
        response.setExecuteResponse(executeResponse);
        response.setTransactionId(transactionId);
        response.setMopayStatus(transaction.getMopayStatus());
        response.setEfasheStatus(transaction.getEfasheStatus());
        response.setMessage(transaction.getMessage());
        response.setPollEndpoint(transaction.getPollEndpoint());
        response.setRetryAfterSecs(transaction.getRetryAfterSecs());
        
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
        
        // Transfer 2: Customer Cashback (planned or sent)
        // Show even if not sent yet (cashbackSent check removed)
        if (transaction.getCustomerCashbackAmount() != null 
            && transaction.getCustomerCashbackAmount().compareTo(BigDecimal.ZERO) > 0) {
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
        
        // Transfer 3: Besoft Share (planned or sent)
        // Show even if not sent yet (cashbackSent check removed)
        if (transaction.getBesoftShareAmount() != null 
            && transaction.getBesoftShareAmount().compareTo(BigDecimal.ZERO) > 0) {
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
     * All transfers use the same transaction ID as the main transaction
     */
    private void sendCashbackTransfers(EfasheTransaction transaction, String mainTransactionId) {
        logger.info("=== sendCashbackTransfers START ===");
        logger.info("Transaction ID: {}", transaction.getTransactionId());
        logger.info("Cashback Sent Flag: {}", transaction.getCashbackSent());
        logger.info("Customer Cashback Amount: {}", transaction.getCustomerCashbackAmount());
        logger.info("Besoft Share Amount: {}", transaction.getBesoftShareAmount());
        logger.info("Full Amount Phone: {}", transaction.getFullAmountPhone());
        logger.info("Customer Phone: {}", transaction.getCustomerPhone());
        logger.info("Cashback Phone: {}", transaction.getCashbackPhone());
        
        if (transaction.getCashbackSent() != null && transaction.getCashbackSent()) {
            logger.info("Cashback transfers already sent for transaction: {}, skipping", transaction.getTransactionId());
            logger.info("=== sendCashbackTransfers END (already sent) ===");
            return;
        }
        
        if (transaction.getCustomerCashbackAmount() == null || transaction.getBesoftShareAmount() == null) {
            logger.warn("Cashback amounts not set for transaction: {} - Customer: {}, Besoft: {}", 
                transaction.getTransactionId(), 
                transaction.getCustomerCashbackAmount(), 
                transaction.getBesoftShareAmount());
            return;
        }
        
        logger.info("Starting cashback transfers for transaction: {} - Customer: {} RWF, Besoft: {} RWF", 
            transaction.getTransactionId(), 
            transaction.getCustomerCashbackAmount(), 
            transaction.getBesoftShareAmount());
        
        try {
            String normalizedFullAmountPhone = normalizePhoneTo12Digits(transaction.getFullAmountPhone());
            String normalizedCustomerPhone = normalizePhoneTo12Digits(transaction.getCustomerPhone());
            String normalizedBesoftPhone = normalizePhoneTo12Digits(transaction.getCashbackPhone());
            
            boolean customerCashbackSent = false;
            boolean besoftShareSent = false;
            
            // Send customer cashback transfer
            if (transaction.getCustomerCashbackAmount().compareTo(BigDecimal.ZERO) > 0) {
                logger.info("=== Sending Customer Cashback Transfer ===");
                logger.info("From: {}, To: {}, Amount: {}", normalizedFullAmountPhone, normalizedCustomerPhone, transaction.getCustomerCashbackAmount());
                try {
                    sendCashbackTransfer(
                        normalizedFullAmountPhone,
                        normalizedCustomerPhone,
                        transaction.getCustomerCashbackAmount(),
                        "EFASHE " + transaction.getServiceType() + " - Customer cashback",
                        "CustomerCashback",
                        mainTransactionId // Use same transaction ID as main transaction
                    );
                    customerCashbackSent = true;
                    logger.info("✅ Customer cashback transfer sent successfully - Amount: {}, From: {}, To: {}", 
                        transaction.getCustomerCashbackAmount(), normalizedFullAmountPhone, normalizedCustomerPhone);
                } catch (Exception e) {
                    logger.error("❌ Failed to send customer cashback transfer - Amount: {}, From: {}, To: {}: ", 
                        transaction.getCustomerCashbackAmount(), normalizedFullAmountPhone, normalizedCustomerPhone, e);
                    e.printStackTrace();
                }
            } else {
                logger.info("Customer cashback amount is 0 or null, skipping customer cashback transfer");
            }
            
            // Send besoft share transfer
            if (transaction.getBesoftShareAmount().compareTo(BigDecimal.ZERO) > 0) {
                logger.info("=== Sending Besoft Share Transfer ===");
                logger.info("From: {}, To: {}, Amount: {}", normalizedFullAmountPhone, normalizedBesoftPhone, transaction.getBesoftShareAmount());
                try {
                    sendCashbackTransfer(
                        normalizedFullAmountPhone,
                        normalizedBesoftPhone,
                        transaction.getBesoftShareAmount(),
                        "EFASHE " + transaction.getServiceType() + " - Besoft share",
                        "BesoftShare",
                        mainTransactionId // Use same transaction ID as main transaction
                    );
                    besoftShareSent = true;
                    logger.info("✅ Besoft share transfer sent successfully - Amount: {}, From: {}, To: {}", 
                        transaction.getBesoftShareAmount(), normalizedFullAmountPhone, normalizedBesoftPhone);
                } catch (Exception e) {
                    logger.error("❌ Failed to send besoft share transfer - Amount: {}, From: {}, To: {}: ", 
                        transaction.getBesoftShareAmount(), normalizedFullAmountPhone, normalizedBesoftPhone, e);
                    e.printStackTrace();
                }
            } else {
                logger.info("Besoft share amount is 0 or null, skipping besoft share transfer");
            }
            
            // Mark cashback as sent only if at least one transfer was attempted
            // Even if one fails, we mark as sent to avoid infinite retries
            transaction.setCashbackSent(true);
            
            // Update EFASHE status to SUCCESS after cashback transfers are completed
            // This ensures the overall transaction status reflects completion
            transaction.setEfasheStatus("SUCCESS");
            
            if (customerCashbackSent && besoftShareSent) {
                transaction.setMessage("EFASHE transaction completed successfully. Cashback transfers sent to customer and besoft.");
                logger.info("Both cashback transfers successful, EFASHE status updated to SUCCESS for transaction: {}", 
                    transaction.getTransactionId());
            } else if (customerCashbackSent || besoftShareSent) {
                transaction.setMessage("EFASHE transaction completed. Some cashback transfers may have failed.");
                logger.warn("Partial cashback transfer success for transaction: {} - Customer: {}, Besoft: {}. Status updated to SUCCESS.", 
                    transaction.getTransactionId(), customerCashbackSent, besoftShareSent);
            } else {
                transaction.setMessage("EFASHE transaction completed, but cashback transfers failed.");
                logger.error("All cashback transfers failed for transaction: {}, but status updated to SUCCESS", transaction.getTransactionId());
            }
            
            // Update existing transaction (never create new row)
            efasheTransactionRepository.save(transaction);
            logger.info("Updated existing transaction row - Transaction ID: {}, Cashback transfers completed - Customer: {}, Besoft: {}, EFASHE Status: SUCCESS", 
                transaction.getTransactionId(), customerCashbackSent, besoftShareSent);
            logger.info("=== sendCashbackTransfers END ===");
        } catch (Exception e) {
            logger.error("=== ERROR in sendCashbackTransfers ===");
            logger.error("Transaction ID: {}", transaction.getTransactionId());
            logger.error("Error sending cashback transfers for transaction {}: ", transaction.getTransactionId(), e);
            e.printStackTrace();
            logger.error("=== END ERROR ===");
            // Don't fail the whole transaction if cashback transfer fails
        }
    }
    
    /**
     * Send a single cashback transfer via MoPay
     * Creates a new MoPay payment from Full Amount Phone to recipient
     * Uses the same transaction ID as the main transaction
     */
    private void sendCashbackTransfer(String fromPhone, String toPhone, BigDecimal amount, String message, String transferType, String mainTransactionId) {
        logger.info("=== sendCashbackTransfer START ===");
        logger.info("Type: {}, Amount: {}, From: {}, To: {}, Message: {}", transferType, amount, fromPhone, toPhone, message);
        logger.info("Using main transaction ID: {} as base for cashback transfer", mainTransactionId);
        try {
            // MoPay doesn't allow reusing the same transaction_id in separate requests
            // Generate a unique transaction ID for this cashback transfer, but related to the main one
            // Format: {mainTransactionId}-{transferType} to keep them related
            String cashbackTransactionId = mainTransactionId + "-" + transferType;
            logger.info("✅ Using unique transaction ID for cashback transfer: {} (related to main: {})", 
                cashbackTransactionId, mainTransactionId);
            
            // Build MoPay request for cashback transfer
            MoPayInitiateRequest cashbackRequest = new MoPayInitiateRequest();
            cashbackRequest.setTransaction_id(cashbackTransactionId); // Same transaction_id as full amount transfer
            cashbackRequest.setAmount(amount);
            cashbackRequest.setCurrency("RWF");
            cashbackRequest.setPhone(fromPhone); // Phone as string
            cashbackRequest.setPayment_mode("MOBILE");
            cashbackRequest.setMessage(message);
            
            // Create transfer to recipient
            List<MoPayInitiateRequest.Transfer> transfers = new ArrayList<>();
            MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
            transfer.setAmount(amount);
            // Convert phone string to Long (MoPay API requires number in transfers)
            Long toPhoneLong = Long.parseLong(toPhone);
            transfer.setPhone(toPhoneLong); // Phone as number (Long)
            transfer.setMessage(message);
            // Note: Each MoPay request needs a unique transaction_id (MoPay doesn't allow reusing same ID in separate requests)
            // This cashback transfer uses a unique transaction_id related to the main transaction
            transfers.add(transfer);
            logger.info("Created cashback transfer - Type: {}, Amount: {}, To Phone (Long): {}, Transaction ID: {} (unique for this request, related to main: {})", 
                transferType, amount, toPhoneLong, cashbackTransactionId, mainTransactionId);
            cashbackRequest.setTransfers(transfers);
            
            logger.info("MoPay cashback request - Transaction ID: {}, Amount: {}, From: {}, To: {}", 
                cashbackTransactionId, amount, fromPhone, toPhone);
            
            // Initiate the cashback transfer
            logger.info("Calling MoPayService.initiatePayment for cashback transfer...");
            MoPayResponse cashbackResponse = moPayService.initiatePayment(cashbackRequest);
            logger.info("MoPayService.initiatePayment returned - Success: {}, Status: {}, Transaction ID: {}", 
                cashbackResponse != null ? cashbackResponse.getSuccess() : null,
                cashbackResponse != null ? cashbackResponse.getStatus() : null,
                cashbackResponse != null ? cashbackResponse.getTransactionId() : null);
            
            if (cashbackResponse != null && cashbackResponse.getSuccess() != null && cashbackResponse.getSuccess()) {
                logger.info("✅ Cashback transfer initiated successfully - Type: {}, Amount: {}, From: {}, To: {}, Transaction ID: {}", 
                    transferType, amount, fromPhone, toPhone, cashbackResponse.getTransactionId());
            } else {
                logger.error("❌ Cashback transfer failed - Type: {}, Amount: {}, From: {}, To: {}, Response: {}", 
                    transferType, amount, fromPhone, toPhone, cashbackResponse);
            }
            logger.info("=== sendCashbackTransfer END ===");
        } catch (Exception e) {
            logger.error("=== ERROR in sendCashbackTransfer ===");
            logger.error("Type: {}, Amount: {}, From: {}, To: {}", transferType, amount, fromPhone, toPhone);
            logger.error("Error initiating cashback transfer: ", e);
            e.printStackTrace();
            logger.error("=== END ERROR ===");
            throw e;
        }
    }
    
    /**
     * Convert EfasheServiceType to EFASHE verticalId
     * Based on EFASHE API verticals list:
     * - airtime: Airtime
     * - ers: Voice and Data Bundles (MTN)
     * - paytv: Pay Television (TV)
     * - tax: RRA Taxes (RRA)
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
                return "ers"; // Voice and Data Bundles
            case RRA:
                return "tax"; // RRA Taxes
            case TV:
                return "paytv"; // Pay Television
            case ELECTRICITY:
                return "electricity";
            default:
                return "airtime";
        }
    }

    /**
     * Get list of verticals from EFASHE API
     * Delegates to EfasheApiService
     */
    public Object getVerticals() {
        return efasheApiService.getVerticals();
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
     * Generates a unique transaction ID starting with "EFASHEPCHI"
     * Format: EFASHEPCHI + timestamp + UUID (first 6 chars)
     * Example: EFASHEPCHI17685808528325A1B2C3
     * Uses UUID for maximum randomness - much better than simple random numbers
     * Always generates a new ID - never reuses existing ones
     */
    private String generateUniqueEfasheTransactionId() {
        // Generate a new unique transaction ID for this request
        // Uses timestamp + truncated UUID for maximum randomness and uniqueness
        // Format: EFASHEPCHI + timestamp + UUID (first 6 chars, no dashes)
        // Always generates a new ID - never reuses existing ones
        
        long timestamp = System.currentTimeMillis();
        
        // Generate UUID and take first 6 characters (most random part)
        // This provides much better randomness than simple hex random
        // 6 chars = 16^6 = 16,777,216 possibilities per millisecond
        String uuid = java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String uuidPart = uuid.substring(0, 6); // First 6 chars of UUID
        
        // Format: EFASHEPCHI + timestamp + UUID (6 chars)
        // Example: EFASHEPCHI17685808528325A1B2C3
        // Shorter format with excellent randomness from UUID
        String transactionId = "EFASHEPCHI" + timestamp + uuidPart;
        
        return transactionId;
    }
    
    /**
     * Send WhatsApp notification to the customer when EFASHE transaction is SUCCESS
     */
    private void sendWhatsAppNotification(EfasheTransaction transaction) {
        try {
            logger.info("=== START WhatsApp Notification for EFASHE Transaction ===");
            logger.info("Transaction ID: {}", transaction.getTransactionId());
            logger.info("Customer Phone: {}", transaction.getCustomerPhone());
            logger.info("Amount: {}", transaction.getAmount());
            logger.info("Service Type: {}", transaction.getServiceType());
            logger.info("EFASHE Status: {}", transaction.getEfasheStatus());
            logger.info("Customer Cashback Amount: {}", transaction.getCustomerCashbackAmount());
            
            if (transaction.getCustomerPhone() == null || transaction.getCustomerPhone().isEmpty()) {
                logger.warn("Customer phone number is null or empty, skipping WhatsApp notification for transaction: {}", 
                    transaction.getTransactionId());
                return;
            }
            
            // Find user by phone number (try multiple formats)
            String normalizedPhone = normalizePhoneTo12Digits(transaction.getCustomerPhone());
            logger.info("Normalized customer phone for WhatsApp: {} (original: {})", normalizedPhone, transaction.getCustomerPhone());
            
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
            
            // Build WhatsApp message
            String serviceName = transaction.getServiceType() != null ? transaction.getServiceType().toString() : "payment";
            String amount = transaction.getAmount() != null ? transaction.getAmount().toPlainString() : "0";
            String cashbackAmount = transaction.getCustomerCashbackAmount() != null 
                ? transaction.getCustomerCashbackAmount().toPlainString() : "0";
            
            // Get customer account name (e.g., "MUHINZI ANDRE" for electricity, TIN owner for RRA)
            String customerAccountName = transaction.getCustomerAccountName();
            String ownerName = (customerAccountName != null && !customerAccountName.trim().isEmpty()) 
                ? customerAccountName.trim() : null;
            
            String message;
            
            // For ELECTRICITY service, include token information if available
            if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                // Extract token from message if it's stored there
                String tokenInfo = "";
                if (transaction.getMessage() != null && transaction.getMessage().contains("Token:")) {
                    // Extract token from message
                    String[] parts = transaction.getMessage().split("Token:");
                    if (parts.length > 1) {
                        tokenInfo = parts[1].trim();
                        // Remove any additional text after token
                        if (tokenInfo.contains(" | ")) {
                            tokenInfo = tokenInfo.split(" \\| ")[0];
                        }
                    }
                }
                
                // Build message with owner name and token
                String ownerInfo = ownerName != null ? " for " + ownerName : "";
                
                if (!tokenInfo.isEmpty()) {
                    message = String.format(
                        "You Paid %s RWF for %s%s. Your token is: %s. Cashback: %s RWF. Thanks for using POCHI App",
                        amount,
                        serviceName,
                        ownerInfo,
                        tokenInfo,
                        cashbackAmount
                    );
                } else {
                    // No token available yet
                    message = String.format(
                        "You Paid %s RWF for %s%s. Cashback: %s RWF. Thanks for using POCHI App",
                        amount,
                        serviceName,
                        ownerInfo,
                        cashbackAmount
                    );
                }
            } else if (transaction.getServiceType() == EfasheServiceType.RRA) {
                // For RRA, include owner name (TIN owner)
                String ownerInfo = ownerName != null ? " for " + ownerName : "";
                message = String.format(
                    "You Paid %s RWF for %s%s. Cashback: %s RWF. Thanks for using POCHI App",
                    amount,
                    serviceName,
                    ownerInfo,
                    cashbackAmount
                );
            } else {
                // For other services (AIRTIME, TV, MTN)
                message = String.format(
                    "You Paid %s, %s and your cash back is %s, Thanks for using POCHI App",
                    serviceName,
                    amount,
                    cashbackAmount
                );
            }
            
            // Use the same phone format as PaymentService (12 digits with 250)
            // WhatsApp service should handle the format conversion if needed
            String whatsappPhone = normalizedPhone;
            logger.info("=== CALLING WhatsApp Service ===");
            logger.info("WhatsApp Phone: {}", whatsappPhone);
            logger.info("WhatsApp Message: {}", message);
            
            // Send WhatsApp notification
            whatsAppService.sendWhatsApp(message, whatsappPhone);
            
            logger.info("=== WhatsApp Service Call Completed ===");
            logger.info("WhatsApp notification sent successfully to customer {} for EFASHE transaction: {}", 
                whatsappPhone, transaction.getTransactionId());
            logger.info("=== END WhatsApp Notification ===");
        } catch (Exception e) {
            logger.error("=== ERROR in WhatsApp Notification ===");
            logger.error("Transaction ID: {}", transaction.getTransactionId());
            logger.error("Customer Phone: {}", transaction.getCustomerPhone());
            logger.error("Error sending WhatsApp notification for EFASHE transaction {}: ", 
                transaction.getTransactionId(), e);
            e.printStackTrace();
            logger.error("=== END WhatsApp Error ===");
            // Don't fail the transaction if WhatsApp fails
        }
    }
    
    /**
     * Get EFASHE transactions with optional filtering by service type, phone number, and date range
     * - ADMIN users can see all transactions (can optionally filter by phone)
     * - RECEIVER users can see all transactions (can optionally filter by phone)
     * - USER users can only see their own transactions (automatically filtered by their phone number)
     * @param serviceType Optional service type filter (AIRTIME, RRA, TV, MTN)
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
        boolean isUser = false;
        String userPhone = null;
        
        if (authentication != null && authentication.isAuthenticated()) {
            // Check user roles
            List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(auth -> auth.toString())
                .toList();
            
            isAdmin = authorities.contains("ROLE_ADMIN");
            isReceiver = authorities.contains("ROLE_RECEIVER");
            isUser = authorities.contains("ROLE_USER");
            
            // For USER tokens, the subject is the phone number
            if (isUser && !isAdmin && !isReceiver) {
                userPhone = authentication.getName();
                logger.info("User detected, will filter by user's phone number: {}", userPhone);
            } else if (isAdmin) {
                logger.info("Admin user detected, can see all transactions");
            } else if (isReceiver) {
                logger.info("Receiver user detected, can see all transactions");
            }
        }
        
        // Normalize phone number for filtering
        String normalizedPhone = null;
        
        // ADMIN and RECEIVER can see all transactions (or filter by phone parameter if provided)
        if (isAdmin || isReceiver) {
            // ADMIN/RECEIVER: Use provided phone filter if any
            if (phone != null && !phone.trim().isEmpty()) {
                try {
                    normalizedPhone = normalizePhoneTo12Digits(phone);
                    logger.info("{} filtering by phone: {} -> {}", isAdmin ? "Admin" : "Receiver", phone, normalizedPhone);
                } catch (Exception e) {
                    logger.warn("Invalid phone number format for filtering: {}, error: {}", phone, e.getMessage());
                    throw new RuntimeException("Invalid phone number format: " + phone);
                }
            }
        } else if (isUser) {
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
        } else {
            throw new RuntimeException("Unauthorized: User must have ADMIN, RECEIVER, or USER role");
        }
        
        String roleInfo = isAdmin ? "ADMIN" : (isReceiver ? "RECEIVER" : (isUser ? "USER" : "UNKNOWN"));
        logger.info("Fetching EFASHE transactions - Role: {}, ServiceType: {}, Phone: {} (normalized: {}), Page: {}, Size: {}, FromDate: {}, ToDate: {}", 
            roleInfo, serviceType, phone, normalizedPhone, page, size, fromDate, toDate);
        
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

