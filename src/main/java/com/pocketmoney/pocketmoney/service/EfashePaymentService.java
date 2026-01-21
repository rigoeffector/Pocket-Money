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

    public EfasheInitiateResponse initiatePayment(EfasheInitiateRequest request) {
        logger.info("Initiating EFASHE payment - Service: {}, Amount: {}, Phone: {}", 
            request.getServiceType(), request.getAmount(), request.getPhone());

        // Validate amount based on service type
        // Amount is optional for RRA (will be taken from vendMin in validate response), required for all other services
        if (request.getServiceType() != EfasheServiceType.RRA) {
            if (request.getAmount() == null) {
                throw new RuntimeException("Amount is required for service type: " + request.getServiceType());
            }
            if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("Amount must be positive for service type: " + request.getServiceType());
            }
        }

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
        
        // For RRA service type, always use vendMin from validate response (ignore amount from request)
        if (request.getServiceType() == EfasheServiceType.RRA) {
            if (validateResponse.getVendMin() != null) {
                amount = BigDecimal.valueOf(validateResponse.getVendMin());
                logger.info("RRA service - Using vendMin from validate response: {} (ignoring amount from request: {})", 
                    amount, request.getAmount());
            } else {
                throw new RuntimeException("RRA service requires vendMin from validate response, but it is not available");
            }
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
        
        logger.info("=== EFASHE Validation Complete - Storing transaction with validated=INITIAL ===");

        // ===================================================================
        // STEP 2: Store transaction with validated=INITIAL (MoPay will be called later via /process endpoint)
        // ===================================================================
        // Amount is now set: either from request or from vendMin (for RRA)
        // Calculate amounts based on percentages
        BigDecimal customerCashbackAmount = BigDecimal.ZERO;
        BigDecimal agentCommissionAmount = BigDecimal.ZERO;
        BigDecimal besoftShareAmount = BigDecimal.ZERO;
        BigDecimal fullAmountPhoneReceives = BigDecimal.ZERO;
        
        if (amount != null) {
            // Calculate amounts based on percentages and round to whole numbers (MoPay requirement)
        BigDecimal agentCommissionPercent = settingsResponse.getAgentCommissionPercentage();
        BigDecimal customerCashbackPercent = settingsResponse.getCustomerCashbackPercentage();
        BigDecimal besoftSharePercent = settingsResponse.getBesoftSharePercentage();

            customerCashbackAmount = amount.multiply(customerCashbackPercent)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            .setScale(0, RoundingMode.HALF_UP); // Round to whole number
            agentCommissionAmount = amount.multiply(agentCommissionPercent)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            .setScale(0, RoundingMode.HALF_UP); // Round to whole number
            besoftShareAmount = amount.multiply(besoftSharePercent)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            .setScale(0, RoundingMode.HALF_UP); // Round to whole number

        // Full amount phone receives: amount - (customerCashback + besoftShare)
        // Customer gets cashback back and Bepay phone gets their share
        // Agent Commission is included in the full amount phone's portion
            fullAmountPhoneReceives = amount.subtract(customerCashbackAmount)
            .subtract(besoftShareAmount)
            .setScale(0, RoundingMode.HALF_UP); // Round to whole number

        logger.info("Amount breakdown - Total: {}, Customer Cashback: {}, Bepay Share: {}, Agent Commission: {}, Full Amount Phone (remaining after cashback and bepay): {}",
            amount, customerCashbackAmount, besoftShareAmount, agentCommissionAmount, fullAmountPhoneReceives);

        // Validate that fullAmountPhoneReceives is not negative
        if (fullAmountPhoneReceives.compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("Invalid percentage configuration: Total percentages exceed 100%. Full amount phone would receive negative amount: " + fullAmountPhoneReceives);
            }
        } else {
            logger.info("RRA service - Amount is null, skipping amount calculations");
        }

        // Generate a temporary transaction ID (UUID-based) - will be updated with MoPay ID when processed
        String tempTransactionId = "EFASHE-" + java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase();
        
        // Store transaction record in database with validated=INITIAL
        // MoPay will be called later when user calls /process endpoint
        EfasheTransaction transaction = new EfasheTransaction();
        transaction.setTransactionId(tempTransactionId); // Temporary ID, will be updated with MoPay ID when processed
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
        transaction.setValidated("INITIAL"); // Mark as validated but not yet processed
        
        // Set INITIAL STATUS - MoPay not called yet, so statuses are null/PENDING
        transaction.setInitialMopayStatus(null); // Will be set when MoPay is called
        transaction.setInitialEfasheStatus("PENDING"); // Store initial EFASHE status (never changes)
        
        // Set CURRENT STATUS - MoPay not called yet
        transaction.setMopayStatus(null); // Will be set when MoPay is called
        transaction.setEfasheStatus("PENDING"); // Current status (will be updated on status check)
        
        transaction.setMessage(request.getMessage() != null ? request.getMessage() : 
            "EFASHE " + request.getServiceType() + " payment");
        
        // For ELECTRICITY and RRA services, if delivery method is SMS, set deliverTo to customer phone
        // This will be used when we execute the EFASHE transaction
        if ("sms".equals(deliveryMethodId)) {
            transaction.setDeliverTo(normalizedCustomerPhone); // Customer phone for SMS delivery
            logger.info("{} service - Delivery method is SMS, setting deliverTo to customer phone: {}", 
                request.getServiceType(), normalizedCustomerPhone);
        }
        
        // Store cashback amounts and phone numbers for reference (will be used when processing MoPay)
        transaction.setCustomerCashbackAmount(customerCashbackAmount);
        transaction.setBesoftShareAmount(besoftShareAmount);
        transaction.setFullAmountPhone(normalizePhoneTo12Digits(settingsResponse.getFullAmountPhoneNumber()));
        transaction.setCashbackPhone(normalizePhoneTo12Digits(settingsResponse.getCashbackPhoneNumber()));
        transaction.setCashbackSent(false); // Not sent yet - will be sent when MoPay is processed
        
        // Store payment mode and callback URL for later MoPay processing
        transaction.setPaymentMode(request.getPayment_mode());
        transaction.setCallbackUrl(request.getCallback_url());
        
        // Save transaction - creates a NEW row in database for each initiation
        efasheTransactionRepository.save(transaction);
        logger.info("Saved EFASHE transaction record with validated=INITIAL - Transaction ID: {}", tempTransactionId);

        // Build response - normalize all phone numbers to consistent format
        EfasheInitiateResponse response = new EfasheInitiateResponse();
        response.setTransactionId(tempTransactionId); // Temporary transaction ID
        response.setServiceType(request.getServiceType());
        response.setAmount(amount);
        // Set normalized phone as string in response
        response.setCustomerPhone(normalizedCustomerPhone);
        // Include customer account name from validate response
        response.setCustomerAccountName(customerAccountName);
        response.setMoPayResponse(null); // No MoPay response yet
        // Include complete EFASHE validate response
        response.setEfasheValidateResponse(validateResponse);
        // Return normalized phone numbers in response (12 digits with 250 prefix)
        response.setFullAmountPhone(normalizePhoneTo12Digits(settingsResponse.getFullAmountPhoneNumber()));
        response.setCashbackPhone(normalizePhoneTo12Digits(settingsResponse.getCashbackPhoneNumber()));
        response.setCustomerCashbackAmount(customerCashbackAmount);
        response.setAgentCommissionAmount(agentCommissionAmount);
        response.setBesoftShareAmount(besoftShareAmount);
        response.setFullAmountPhoneReceives(fullAmountPhoneReceives);
        response.setValidated("INITIAL"); // Mark as validated but not processed

        logger.info("EFASHE payment validated successfully - Transaction ID: {}, validated: INITIAL", tempTransactionId);

        return response;
    }

    /**
     * Initiate EFASHE payment for others (buying airtime for another person)
     * This method allows buying airtime for another person:
     * - phone: used for MoPay debit (the person paying)
     * - anotherPhoneNumber: used for EFASHE validate (the person receiving airtime)
     * 
     * Only works for AIRTIME service type
     */
    public EfasheInitiateResponse initiatePaymentForOther(EfasheInitiateForOtherRequest request) {
        logger.info("Initiating EFASHE payment for other - Service: {}, Amount: {}, Payer Phone: {}, Recipient Phone: {}", 
            request.getServiceType(), request.getAmount(), request.getPhone(), request.getAnotherPhoneNumber());

        // Validate that service type is AIRTIME
        if (request.getServiceType() != EfasheServiceType.AIRTIME) {
            throw new RuntimeException("This endpoint only supports AIRTIME service type");
        }

        // Get EFASHE settings for the service type
        EfasheSettingsResponse settingsResponse = efasheSettingsService.getSettingsByServiceType(request.getServiceType());

        BigDecimal amount = request.getAmount();
        
        // Normalize payer phone (for MoPay debit)
        String normalizedPayerPhone = normalizePhoneTo12Digits(request.getPhone());
        
        // Normalize recipient phone (for EFASHE validate)
        String normalizedRecipientPhone = normalizePhoneTo12Digits(request.getAnotherPhoneNumber());
        
        // Convert recipient phone to account number format for EFASHE validate
        // Remove 250 prefix and add 0 prefix: 250784638201 -> 0784638201
        String customerAccountNumber = normalizedRecipientPhone.startsWith("250") 
            ? "0" + normalizedRecipientPhone.substring(3) 
            : (normalizedRecipientPhone.startsWith("0") ? normalizedRecipientPhone : "0" + normalizedRecipientPhone);
        
        logger.info("Buying for other - Payer Phone (MoPay): {}, Recipient Phone (EFASHE): {}, Account Number: {}", 
            normalizedPayerPhone, normalizedRecipientPhone, customerAccountNumber);

        // ===================================================================
        // STEP 1: Validate with EFASHE BEFORE initiating MoPay
        // Use recipient phone for validate
        // ===================================================================
        logger.info("=== STEP 1: Validating with EFASHE (recipient phone) BEFORE MoPay initiation ===");
        EfasheValidateRequest validateRequest = new EfasheValidateRequest();
        validateRequest.setVerticalId(getVerticalId(request.getServiceType()));
        validateRequest.setCustomerAccountNumber(customerAccountNumber);
        
        logger.info("Calling EFASHE validate - Vertical: {}, Customer Account Number (recipient): {}", 
            validateRequest.getVerticalId(), validateRequest.getCustomerAccountNumber());
        
        EfasheValidateResponse validateResponse = efasheApiService.validateAccount(validateRequest);
        
        // Validate MUST succeed before proceeding with MoPay
        if (validateResponse == null || validateResponse.getTrxId() == null || validateResponse.getTrxId().trim().isEmpty()) {
            String errorMsg = "EFASHE validation failed - Invalid recipient account or service unavailable";
            if (validateResponse != null && validateResponse.getTrxResult() != null) {
                errorMsg = "EFASHE validation failed: " + validateResponse.getTrxResult();
            }
            logger.error("EFASHE validation failed - Cannot proceed with MoPay. Recipient Account: {}, Service: {}", 
                customerAccountNumber, request.getServiceType());
            throw new RuntimeException(errorMsg);
        }
        
        logger.info("EFASHE validation successful - TrxId: {}, Recipient Account: {}", 
            validateResponse.getTrxId(), customerAccountNumber);
        
        // Store customer account name if available
        String customerAccountName = null;
        if (validateResponse.getCustomerAccountName() != null && !validateResponse.getCustomerAccountName().trim().isEmpty()) {
            customerAccountName = validateResponse.getCustomerAccountName().trim();
            logger.info("Recipient Account Name from validate: {}", customerAccountName);
        }
        
        // Determine delivery method from validate response
        String deliveryMethodId = "direct_topup"; // Default for AIRTIME
        if (validateResponse.getDeliveryMethods() != null && !validateResponse.getDeliveryMethods().isEmpty()) {
            boolean hasDirectTopup = validateResponse.getDeliveryMethods().stream()
                .anyMatch(dm -> "direct_topup".equals(dm.getId()));
            
            if (hasDirectTopup) {
                deliveryMethodId = "direct_topup";
            } else {
                deliveryMethodId = validateResponse.getDeliveryMethods().get(0).getId();
            }
        }
        
        logger.info("=== EFASHE Validation Complete - Storing transaction with validated=INITIAL ===");

        // ===================================================================
        // STEP 2: Store transaction with validated=INITIAL (MoPay will be called later via /process endpoint)
        // ===================================================================
        BigDecimal agentCommissionPercent = settingsResponse.getAgentCommissionPercentage();
        BigDecimal customerCashbackPercent = settingsResponse.getCustomerCashbackPercentage();
        BigDecimal besoftSharePercent = settingsResponse.getBesoftSharePercentage();

        // Calculate amounts based on percentages and round to whole numbers (MoPay requirement)
        BigDecimal customerCashbackAmount = amount.multiply(customerCashbackPercent)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            .setScale(0, RoundingMode.HALF_UP);
        BigDecimal agentCommissionAmount = amount.multiply(agentCommissionPercent)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            .setScale(0, RoundingMode.HALF_UP);
        BigDecimal besoftShareAmount = amount.multiply(besoftSharePercent)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            .setScale(0, RoundingMode.HALF_UP);

        BigDecimal fullAmountPhoneReceives = amount.subtract(customerCashbackAmount)
            .subtract(besoftShareAmount)
            .setScale(0, RoundingMode.HALF_UP);

        logger.info("Amount breakdown - Total: {}, Customer Cashback: {}, Besoft Share: {}, Agent Commission: {}, Full Amount Phone (remaining after cashback and besoft): {}",
            amount, customerCashbackAmount, besoftShareAmount, agentCommissionAmount, fullAmountPhoneReceives);

        if (fullAmountPhoneReceives.compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("Invalid percentage configuration: Total percentages exceed 100%. Full amount phone would receive negative amount: " + fullAmountPhoneReceives);
        }

        // Generate a temporary transaction ID (UUID-based) - will be updated with MoPay ID when processed
        String tempTransactionId = "EFASHE-" + java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase();
        
        // Store transaction record in database with validated=INITIAL
        // MoPay will be called later when user calls /process endpoint
        EfasheTransaction transaction = new EfasheTransaction();
        transaction.setTransactionId(tempTransactionId); // Temporary ID, will be updated with MoPay ID when processed
        transaction.setServiceType(request.getServiceType());
        transaction.setCustomerPhone(normalizedPayerPhone); // Store payer phone (for MoPay)
        transaction.setCustomerAccountNumber(customerAccountNumber); // Store recipient account number (for EFASHE)
        
        // Store validate response data
        transaction.setTrxId(validateResponse.getTrxId());
        if (customerAccountName != null) {
            transaction.setCustomerAccountName(customerAccountName); // Recipient name
        }
        transaction.setDeliveryMethodId(deliveryMethodId);
        transaction.setAmount(amount);
        transaction.setCurrency(request.getCurrency());
        transaction.setValidated("INITIAL"); // Mark as validated but not yet processed
        
        // Set INITIAL STATUS - MoPay not called yet, so statuses are null/PENDING
        transaction.setInitialMopayStatus(null); // Will be set when MoPay is called
        transaction.setInitialEfasheStatus("PENDING"); // Store initial EFASHE status (never changes)
        
        // Set CURRENT STATUS - MoPay not called yet
        transaction.setMopayStatus(null); // Will be set when MoPay is called
        transaction.setEfasheStatus("PENDING"); // Current status (will be updated on status check)
        
        transaction.setMessage(request.getMessage() != null ? request.getMessage() : 
            "EFASHE " + request.getServiceType() + " payment for other");
        
        if ("sms".equals(deliveryMethodId)) {
            transaction.setDeliverTo(normalizedRecipientPhone); // Recipient phone for SMS delivery
            logger.info("Delivery method is SMS, setting deliverTo to recipient phone: {}", normalizedRecipientPhone);
        }
        
        transaction.setCustomerCashbackAmount(customerCashbackAmount);
        transaction.setBesoftShareAmount(besoftShareAmount);
        transaction.setFullAmountPhone(normalizePhoneTo12Digits(settingsResponse.getFullAmountPhoneNumber()));
        transaction.setCashbackPhone(normalizePhoneTo12Digits(settingsResponse.getCashbackPhoneNumber()));
        transaction.setCashbackSent(false); // Not sent yet - will be sent when MoPay is processed
        
        // Store payment mode and callback URL for later MoPay processing
        transaction.setPaymentMode(request.getPayment_mode());
        transaction.setCallbackUrl(request.getCallback_url());
        
        efasheTransactionRepository.save(transaction);
        logger.info("Saved EFASHE transaction record (buying for other) with validated=INITIAL - Transaction ID: {}, Payer: {}, Recipient: {}", 
            tempTransactionId, normalizedPayerPhone, normalizedRecipientPhone);

        // Build response
        EfasheInitiateResponse response = new EfasheInitiateResponse();
        response.setTransactionId(tempTransactionId); // Temporary transaction ID
        response.setServiceType(request.getServiceType());
        response.setAmount(amount);
        response.setCustomerPhone(normalizedPayerPhone); // Payer phone in response
        response.setCustomerAccountName(customerAccountName); // Recipient name
        response.setMoPayResponse(null); // No MoPay response yet
        // Include complete EFASHE validate response
        response.setEfasheValidateResponse(validateResponse);
        response.setFullAmountPhone(normalizePhoneTo12Digits(settingsResponse.getFullAmountPhoneNumber()));
        response.setCashbackPhone(normalizePhoneTo12Digits(settingsResponse.getCashbackPhoneNumber()));
        response.setCustomerCashbackAmount(customerCashbackAmount);
        response.setAgentCommissionAmount(agentCommissionAmount);
        response.setBesoftShareAmount(besoftShareAmount);
        response.setFullAmountPhoneReceives(fullAmountPhoneReceives);
        response.setValidated("INITIAL"); // Mark as validated but not processed

        logger.info("EFASHE payment validated successfully for other - Transaction ID: {}, validated: INITIAL, Payer: {}, Recipient: {}", 
            tempTransactionId, normalizedPayerPhone, normalizedRecipientPhone);

        return response;
    }

    /**
     * Process a validated transaction by calling MoPay
     * Updates validated flag to "PROCESS" and initiates MoPay payment
     * @param transactionId The transaction ID from initiate response
     * @return EfasheInitiateResponse with MoPay response
     */
    @Transactional
    public EfasheInitiateResponse processPayment(String transactionId) {
        logger.info("Processing EFASHE payment - Transaction ID: {}", transactionId);
        
        // Find transaction by ID
        EfasheTransaction transaction = efasheTransactionRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new RuntimeException("EFASHE transaction not found: " + transactionId));
        
        // Check if transaction is in INITIAL state
        if (!"INITIAL".equals(transaction.getValidated())) {
            throw new RuntimeException("Transaction is not in INITIAL state. Current state: " + transaction.getValidated());
        }
        
        logger.info("Found transaction in INITIAL state - Updating to PROCESS and initiating MoPay");
        
        // Update validated flag to PROCESS
        transaction.setValidated("PROCESS");
        
        // Get EFASHE settings for the service type
        EfasheSettingsResponse settingsResponse = efasheSettingsService.getSettingsByServiceType(transaction.getServiceType());
        
        BigDecimal amount = transaction.getAmount();
        
        // For RRA, amount can be null during initiate, but is required for processing
        // For other services, amount should already be set during initiate
        if (amount == null) {
            throw new RuntimeException("Amount is required for processing. Please provide amount for service type: " + transaction.getServiceType());
        }
        
        String normalizedCustomerPhone = transaction.getCustomerPhone();

        // Build MoPay request
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        moPayRequest.setAmount(amount);
        moPayRequest.setCurrency(transaction.getCurrency());
        moPayRequest.setPhone(normalizedCustomerPhone);
        moPayRequest.setPayment_mode(transaction.getPaymentMode());
        moPayRequest.setMessage(transaction.getMessage());
        moPayRequest.setCallback_url(transaction.getCallbackUrl());
        
        // Build transfers array
        List<MoPayInitiateRequest.Transfer> transfers = new ArrayList<>();

        // Transfer 1: Full Amount Phone Number
        MoPayInitiateRequest.Transfer transfer1 = new MoPayInitiateRequest.Transfer();
        BigDecimal fullAmountPhoneReceives = amount.subtract(
            transaction.getCustomerCashbackAmount() != null ? transaction.getCustomerCashbackAmount() : BigDecimal.ZERO)
            .subtract(transaction.getBesoftShareAmount() != null ? transaction.getBesoftShareAmount() : BigDecimal.ZERO)
            .setScale(0, RoundingMode.HALF_UP);
        transfer1.setAmount(fullAmountPhoneReceives);
        String normalizedFullAmountPhone = normalizePhoneTo12Digits(transaction.getFullAmountPhone());
        transfer1.setPhone(Long.parseLong(normalizedFullAmountPhone));
        transfer1.setMessage("EFASHE " + transaction.getServiceType() + " - Full amount");
        transfers.add(transfer1);
        
        // Transfer 2: Customer Cashback
        if (transaction.getCustomerCashbackAmount() != null && transaction.getCustomerCashbackAmount().compareTo(BigDecimal.ZERO) > 0) {
            MoPayInitiateRequest.Transfer transfer2 = new MoPayInitiateRequest.Transfer();
            transfer2.setAmount(transaction.getCustomerCashbackAmount());
            transfer2.setPhone(Long.parseLong(normalizedCustomerPhone));
            transfer2.setMessage("EFASHE " + transaction.getServiceType() + " - Customer cashback");
            transfers.add(transfer2);
        }
        
        // Transfer 3: Besoft Share
        if (transaction.getBesoftShareAmount() != null && transaction.getBesoftShareAmount().compareTo(BigDecimal.ZERO) > 0) {
            String normalizedCashbackPhone = normalizePhoneTo12Digits(transaction.getCashbackPhone());
            MoPayInitiateRequest.Transfer transfer3 = new MoPayInitiateRequest.Transfer();
            transfer3.setAmount(transaction.getBesoftShareAmount());
            transfer3.setPhone(Long.parseLong(normalizedCashbackPhone));
            transfer3.setMessage("EFASHE " + transaction.getServiceType() + " - Besoft share");
            transfers.add(transfer3);
        }

        moPayRequest.setTransfers(transfers);

        // Initiate payment with MoPay
        MoPayResponse moPayResponse = moPayService.initiatePayment(moPayRequest);

        String mopayTransactionId = moPayResponse != null && moPayResponse.getTransactionId() != null 
            ? moPayResponse.getTransactionId() : null;
        
        if (mopayTransactionId == null || mopayTransactionId.isEmpty()) {
            throw new RuntimeException("MoPay did not return a transaction ID. Payment initiation may have failed.");
        }
        
        // Update transaction with MoPay response
        transaction.setTransactionId(mopayTransactionId); // Update with MoPay transaction ID
        transaction.setMopayTransactionId(mopayTransactionId);
        
        String initialMopayStatus = moPayResponse != null && moPayResponse.getStatus() != null 
            ? moPayResponse.getStatus().toString() : "PENDING";
        transaction.setInitialMopayStatus(initialMopayStatus);
        transaction.setMopayStatus(initialMopayStatus);
        transaction.setCashbackSent(true); // Mark as sent
        
        // Save updated transaction
        efasheTransactionRepository.save(transaction);
        logger.info("Updated transaction with MoPay response - Transaction ID: {}, MoPay Transaction ID: {}", 
            transactionId, mopayTransactionId);

        // Build response
        EfasheInitiateResponse response = new EfasheInitiateResponse();
        response.setTransactionId(mopayTransactionId);
        response.setServiceType(transaction.getServiceType());
        response.setAmount(amount);
        response.setCustomerPhone(normalizedCustomerPhone);
        response.setCustomerAccountName(transaction.getCustomerAccountName());
        response.setMoPayResponse(moPayResponse);
        response.setFullAmountPhone(normalizedFullAmountPhone);
        response.setCashbackPhone(normalizePhoneTo12Digits(transaction.getCashbackPhone()));
        response.setCustomerCashbackAmount(transaction.getCustomerCashbackAmount());
        response.setBesoftShareAmount(transaction.getBesoftShareAmount());
        response.setFullAmountPhoneReceives(fullAmountPhoneReceives);
        response.setValidated("PROCESS");

        logger.info("EFASHE payment processed successfully - Transaction ID: {}, MoPay Transaction ID: {}", 
            transactionId, mopayTransactionId);

        return response;
    }

    /**
     * Find EFASHE transaction by ID (tries both EFASHE transaction ID and MoPay transaction ID)
     * @param transactionId Can be either the EFASHE transaction ID or MoPay transaction ID
     * @return Optional containing the transaction if found
     */
    @Transactional(readOnly = true)
    public Optional<EfasheTransaction> findTransactionById(String transactionId) {
        // Try to find transaction by EFASHE transaction ID first, then by MoPay transaction ID
        // This allows the endpoint to work with both transaction ID formats
        Optional<EfasheTransaction> transactionOpt = efasheTransactionRepository.findByTransactionId(transactionId);
        
        if (!transactionOpt.isPresent()) {
            // If not found by EFASHE transaction ID, try MoPay transaction ID
            logger.info("Transaction not found by EFASHE transaction ID, trying MoPay transaction ID: {}", transactionId);
            transactionOpt = efasheTransactionRepository.findByMopayTransactionId(transactionId);
        }
        
        return transactionOpt;
    }
    
    /**
     * Check the status of an EFASHE transaction using either EFASHE transaction ID or MoPay transaction ID
     * If status is SUCCESS (200), automatically triggers EFASHE validate and execute
     * @param transactionId Can be either the EFASHE transaction ID or MoPay transaction ID
     * @return EfasheStatusResponse containing the transaction status and EFASHE responses
     */
    @Transactional
    public EfasheStatusResponse checkTransactionStatus(String transactionId) {
        logger.info("Checking EFASHE transaction status for transaction ID: {} (trying both EFASHE and MoPay IDs)", transactionId);
        
        // Use the helper method to find transaction
        Optional<EfasheTransaction> transactionOpt = findTransactionById(transactionId);
        
        // Get stored transaction record - ALWAYS update existing row, never create new one
        EfasheTransaction transaction = transactionOpt
            .orElseThrow(() -> new RuntimeException("EFASHE transaction not found with ID: " + transactionId + " (tried both EFASHE and MoPay IDs)"));
        
        logger.info("Found existing transaction - ID: {}, EFASHE Transaction ID: {}, MoPay Transaction ID: {}, Current MoPay Status: {}, Current EFASHE Status: {}, Initial MoPay Status: {}, Initial EFASHE Status: {}, PollEndpoint: {}, RetryAfterSecs: {}", 
            transaction.getId(), transaction.getTransactionId(), transaction.getMopayTransactionId(), 
            transaction.getMopayStatus(), transaction.getEfasheStatus(), 
            transaction.getInitialMopayStatus(), transaction.getInitialEfasheStatus(),
            transaction.getPollEndpoint() != null ? transaction.getPollEndpoint() : "NULL",
            transaction.getRetryAfterSecs());
        
        // Use the actual MoPay transaction ID from the transaction record for checking MoPay status
        // The transactionId parameter might be either EFASHE or MoPay ID, but we need the actual MoPay ID for the API call
        String mopayTransactionIdToCheck = transaction.getMopayTransactionId() != null 
            ? transaction.getMopayTransactionId() 
            : transaction.getTransactionId(); // Fallback to transactionId if mopayTransactionId is null
        
        logger.info("Checking MoPay status using transaction ID: {} (from stored transaction record)", mopayTransactionIdToCheck);
        MoPayResponse moPayResponse = moPayService.checkTransactionStatus(mopayTransactionIdToCheck);
        
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
            
            // Log current state for debugging
            logger.info("Status check decision - MoPay SUCCESS: {}, EFASHE Status: {}, PollEndpoint: {}, CashbackSent: {}", 
                isSuccess, transaction.getEfasheStatus(), 
                transaction.getPollEndpoint() != null ? transaction.getPollEndpoint() : "NULL",
                transaction.getCashbackSent());
            
            // If EFASHE is already FAILED with a pollEndpoint and MoPay is SUCCESS, try polling instead of executing
            if (isSuccess && "FAILED".equalsIgnoreCase(transaction.getEfasheStatus()) 
                && transaction.getPollEndpoint() != null 
                && !transaction.getPollEndpoint().isEmpty()) {
                
                logger.info("MoPay is SUCCESS and EFASHE is FAILED with pollEndpoint - Retrying automatic polling instead of executing - PollEndpoint: {}", 
                    transaction.getPollEndpoint());
                
                Integer retryAfterSecs = transaction.getRetryAfterSecs() != null && transaction.getRetryAfterSecs() > 0 
                    ? transaction.getRetryAfterSecs() : 10;
                
                boolean pollSuccess = pollUntilSuccess(transaction, transaction.getPollEndpoint(), retryAfterSecs);
                
                if (pollSuccess) {
                    logger.info("✅ Retry polling completed - Transaction ID: {}, EFASHE Status: SUCCESS", 
                        transaction.getTransactionId());
                } else {
                    logger.warn("⚠️ Retry polling completed but status is still not SUCCESS - Transaction ID: {}, Status: {}", 
                        transaction.getTransactionId(), transaction.getEfasheStatus());
                }
            } else if (isSuccess && !"SUCCESS".equalsIgnoreCase(transaction.getEfasheStatus())) {
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
                        // Always send amount (including for RRA) - EFASHE requires it even for RRA
                        // For RRA, the amount comes from vendMin (already set during initiate)
                        if (transaction.getAmount() != null) {
                        executeRequest.setAmount(transaction.getAmount());
                            logger.info("EFASHE execute - Setting amount: {} for service type: {}", 
                                transaction.getAmount(), transaction.getServiceType());
                        } else {
                            logger.error("EFASHE execute - Amount is null for transaction: {}", transaction.getTransactionId());
                            throw new RuntimeException("Amount is required for EFASHE execute but is null in transaction");
                        }
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
                            
                            // For ELECTRICITY service, extract and store token and KWH information if available
                            if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                                String token = executeResponse.getToken();
                                String kwh = null;
                                
                                // Try to extract KWH from extraInfo if available
                                if (executeResponse.getExtraInfo() != null) {
                                    try {
                                        // extraInfo might be a Map or JSON object
                                        if (executeResponse.getExtraInfo() instanceof java.util.Map) {
                                            @SuppressWarnings("unchecked")
                                            java.util.Map<String, Object> extraInfoMap = (java.util.Map<String, Object>) executeResponse.getExtraInfo();
                                            if (extraInfoMap.containsKey("kwh") || extraInfoMap.containsKey("KWH") || extraInfoMap.containsKey("kWh")) {
                                                Object kwhObj = extraInfoMap.getOrDefault("kwh", extraInfoMap.getOrDefault("KWH", extraInfoMap.get("kWh")));
                                                if (kwhObj != null) {
                                                    kwh = kwhObj.toString();
                                                    logger.info("ELECTRICITY service - KWH extracted from extraInfo: {}", kwh);
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        logger.debug("Error extracting KWH from extraInfo: {}", e.getMessage());
                                    }
                                }
                                
                                // Build message with token and KWH
                                String existingMessage = executeResponse.getMessage() != null ? executeResponse.getMessage() : "";
                                StringBuilder messageBuilder = new StringBuilder(existingMessage);
                                
                                if (token != null && !token.isEmpty()) {
                                    if (messageBuilder.length() > 0) {
                                        messageBuilder.append(" | ");
                                    }
                                    messageBuilder.append("Token: ").append(token);
                                    logger.info("ELECTRICITY service - Token received: {}", token);
                                }
                                
                                if (kwh != null && !kwh.isEmpty()) {
                                    if (messageBuilder.length() > 0) {
                                        messageBuilder.append(" | ");
                                    }
                                    messageBuilder.append("KWH: ").append(kwh);
                                    logger.info("ELECTRICITY service - KWH received: {}", kwh);
                                }
                                
                                transaction.setMessage(messageBuilder.toString());
                                
                                // Call /electricity/tokens endpoint to get token and save it
                                try {
                                    String meterNumber = transaction.getCustomerAccountNumber();
                                    if (meterNumber != null && !meterNumber.trim().isEmpty()) {
                                        logger.info("ELECTRICITY service - Calling /electricity/tokens endpoint for meter: {}", meterNumber);
                                        ElectricityTokensResponse tokensResponse = efasheApiService.getElectricityTokens(meterNumber, 1);
                                        
                                        if (tokensResponse != null && tokensResponse.getData() != null && !tokensResponse.getData().isEmpty()) {
                                            ElectricityTokensResponse.ElectricityTokenData firstTokenData = tokensResponse.getData().get(0);
                                            String firstToken = firstTokenData.getToken();
                                            
                                            if (firstToken != null && !firstToken.trim().isEmpty()) {
                                                // Save the first token to the database
                                                transaction.setToken(firstToken);
                                                logger.info("ELECTRICITY service - Saved first token from /electricity/tokens (execute): {}", firstToken);
                                                
                                                // Update message with the token from tokens endpoint if not already present
                                                if (token == null || token.isEmpty() || !messageBuilder.toString().contains("Token:")) {
                                                    if (messageBuilder.length() > 0) {
                                                        messageBuilder.append(" | ");
                                                    }
                                                    messageBuilder.append("Token: ").append(firstToken);
                                                    transaction.setMessage(messageBuilder.toString());
                                                    logger.info("ELECTRICITY service - Updated message with token from /electricity/tokens endpoint (execute)");
                                                }
                                                
                                                // Also update KWH if available from tokens response
                                                if (firstTokenData.getUnits() != null) {
                                                    String unitsStr = String.format("%.1f", firstTokenData.getUnits());
                                                    if (!messageBuilder.toString().contains("KWH:")) {
                                                        if (messageBuilder.length() > 0) {
                                                            messageBuilder.append(" | ");
                                                        }
                                                        messageBuilder.append("KWH: ").append(unitsStr);
                                                        transaction.setMessage(messageBuilder.toString());
                                                        logger.info("ELECTRICITY service - Updated message with KWH from /electricity/tokens (execute): {}", unitsStr);
                                                    }
                                                }
                                            } else {
                                                logger.warn("ELECTRICITY service - First token from /electricity/tokens (execute) is null or empty");
                                            }
                                        } else {
                                            logger.warn("ELECTRICITY service - No token data returned from /electricity/tokens endpoint (execute)");
                                        }
                                    } else {
                                        logger.warn("ELECTRICITY service - Cannot call /electricity/tokens (execute): meter number is null or empty");
                                    }
                                } catch (Exception e) {
                                    logger.error("ELECTRICITY service - Error calling /electricity/tokens endpoint (execute): ", e);
                                    // Don't fail the transaction if tokens endpoint fails - continue with existing token if available
                                }
                            } else {
                                transaction.setMessage(executeResponse.getMessage());
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
                                
                                // Store pollEndpoint and retryAfterSecs for later polling
                                transaction.setPollEndpoint(executeResponse.getPollEndpoint());
                                transaction.setRetryAfterSecs(executeResponse.getRetryAfterSecs());
                                            efasheTransactionRepository.save(transaction);
                                            
                                // Automatic retry polling - poll until SUCCESS or max retries
                                boolean pollSuccess = pollUntilSuccess(transaction, executeResponse.getPollEndpoint(), 
                                    executeResponse.getRetryAfterSecs() != null ? executeResponse.getRetryAfterSecs() : 10);
                                
                                if (!pollSuccess) {
                                    logger.warn("EFASHE polling completed but status is still not SUCCESS - Transaction ID: {}, Status: {}", 
                                        transaction.getTransactionId(), transaction.getEfasheStatus());
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
                
                // After execute attempt (success or failure), check if we need to retry polling for FAILED status
                // This handles cases where execute failed but we have a pollEndpoint to retry
                // Note: We retry even if cashback is sent, because EFASHE might have failed after cashback was sent
                if ("FAILED".equalsIgnoreCase(transaction.getEfasheStatus()) 
                    && transaction.getPollEndpoint() != null 
                    && !transaction.getPollEndpoint().isEmpty()
                    && isSuccess) { // Only retry when MoPay is SUCCESS
                    
                    logger.info("MoPay is SUCCESS and EFASHE is FAILED after execute attempt - Retrying automatic polling - PollEndpoint: {}", 
                        transaction.getPollEndpoint());
                    
                    Integer retryAfterSecs = transaction.getRetryAfterSecs() != null && transaction.getRetryAfterSecs() > 0 
                        ? transaction.getRetryAfterSecs() : 10;
                    
                    boolean pollSuccess = pollUntilSuccess(transaction, transaction.getPollEndpoint(), retryAfterSecs);
                    
                    if (pollSuccess) {
                        logger.info("✅ Retry polling completed after execute failure - Transaction ID: {}, EFASHE Status: SUCCESS", 
                            transaction.getTransactionId());
                    } else {
                        logger.warn("⚠️ Retry polling completed but status is still not SUCCESS - Transaction ID: {}, Status: {}", 
                            transaction.getTransactionId(), transaction.getEfasheStatus());
                    }
                }
            } else {
                logger.info("MoPay transaction not yet SUCCESS or already processed - Status: {}, EFASHE Status: {}", 
                    statusCode, transaction.getEfasheStatus());
                
                // If EFASHE is PENDING with pollEndpoint and MoPay is SUCCESS, use automatic retry polling
                // NOTE: If /vend/execute returned HTTP 200, status is already SUCCESS, so we don't poll
                // Only poll if status is still PENDING and we have a pollEndpoint
                // Note: We retry even if cashback is sent, because EFASHE status might still be PENDING
                if ("PENDING".equalsIgnoreCase(transaction.getEfasheStatus()) 
                    && transaction.getPollEndpoint() != null 
                    && !transaction.getPollEndpoint().isEmpty()
                    && isSuccess) { // Only retry when MoPay is SUCCESS
                    
                    logger.info("MoPay is SUCCESS and EFASHE is PENDING - Starting automatic retry polling - PollEndpoint: {}", 
                        transaction.getPollEndpoint());
                    
                    Integer retryAfterSecs = transaction.getRetryAfterSecs() != null && transaction.getRetryAfterSecs() > 0 
                        ? transaction.getRetryAfterSecs() : 10;
                    
                    boolean pollSuccess = pollUntilSuccess(transaction, transaction.getPollEndpoint(), retryAfterSecs);
                    
                    if (pollSuccess) {
                        logger.info("✅ Automatic retry polling completed - Transaction ID: {}, EFASHE Status: SUCCESS", 
                            transaction.getTransactionId());
                            } else {
                        logger.warn("⚠️ Automatic retry polling completed but status is still not SUCCESS - Transaction ID: {}, Status: {}", 
                            transaction.getTransactionId(), transaction.getEfasheStatus());
                    }
                } else if ("PENDING".equalsIgnoreCase(transaction.getEfasheStatus()) 
                    && transaction.getPollEndpoint() != null 
                    && !transaction.getPollEndpoint().isEmpty()
                    && !isSuccess) {
                    logger.info("EFASHE is PENDING but MoPay is not yet SUCCESS - Skipping polling until MoPay succeeds");
                }
                
                // If EFASHE is FAILED with pollEndpoint and MoPay is SUCCESS, retry polling
                // This handles cases where polling failed but the pollEndpoint is still valid
                // Note: We retry even if cashback is sent, because EFASHE might have failed after cashback was sent
                if ("FAILED".equalsIgnoreCase(transaction.getEfasheStatus()) 
                    && transaction.getPollEndpoint() != null 
                    && !transaction.getPollEndpoint().isEmpty()
                    && isSuccess) { // Only retry when MoPay is SUCCESS
                    
                    logger.info("MoPay is SUCCESS and EFASHE is FAILED - Retrying automatic polling - PollEndpoint: {}", 
                        transaction.getPollEndpoint());
                    
                    Integer retryAfterSecs = transaction.getRetryAfterSecs() != null && transaction.getRetryAfterSecs() > 0 
                        ? transaction.getRetryAfterSecs() : 10;
                    
                    boolean pollSuccess = pollUntilSuccess(transaction, transaction.getPollEndpoint(), retryAfterSecs);
                    
                    if (pollSuccess) {
                        logger.info("✅ Retry polling completed - Transaction ID: {}, EFASHE Status: SUCCESS", 
                            transaction.getTransactionId());
                        } else {
                        logger.warn("⚠️ Retry polling completed but status is still not SUCCESS - Transaction ID: {}, Status: {}", 
                            transaction.getTransactionId(), transaction.getEfasheStatus());
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
        
        // For ELECTRICITY transactions, fetch and include electricity tokens response
        if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
            try {
                String meterNumber = transaction.getCustomerAccountNumber();
                if (meterNumber != null && !meterNumber.trim().isEmpty()) {
                    logger.info("ELECTRICITY transaction - Fetching tokens for meter: {}", meterNumber);
                    ElectricityTokensResponse tokensResponse = efasheApiService.getElectricityTokens(meterNumber, 1);
                    response.setElectricityTokens(tokensResponse);
                    logger.info("ELECTRICITY transaction - Tokens response added to status response");
                } else {
                    logger.warn("ELECTRICITY transaction - Cannot fetch tokens: meter number is null or empty");
                }
            } catch (Exception e) {
                logger.error("ELECTRICITY transaction - Error fetching tokens for status response: ", e);
                // Don't fail the status check if tokens endpoint fails
            }
        }
        
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
     * Poll EFASHE status until SUCCESS or max retries
     * Only retries when MoPay status is SUCCESS and EFASHE status is PENDING
     * @param transaction The transaction to poll
     * @param pollEndpoint The poll endpoint URL
     * @param initialRetryAfterSecs Initial wait time before first poll
     * @return true if SUCCESS, false if still PENDING after max retries
     */
    private boolean pollUntilSuccess(EfasheTransaction transaction, String pollEndpoint, Integer initialRetryAfterSecs) {
        logger.info("=== START Automatic Polling with Retry Mechanism ===");
        logger.info("Transaction ID: {}, Poll Endpoint: {}, Initial Retry After: {}s", 
            transaction.getTransactionId(), pollEndpoint, initialRetryAfterSecs);
        
        int maxRetries = 10; // Maximum number of retry attempts
        int retryCount = 0;
        int retryAfterSecs = initialRetryAfterSecs != null && initialRetryAfterSecs > 0 ? initialRetryAfterSecs : 10;
        
        // Wait before first poll
        if (retryAfterSecs > 0) {
            try {
                logger.info("Waiting {} seconds before first poll...", retryAfterSecs);
                Thread.sleep(retryAfterSecs * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting to poll EFASHE status");
                return false;
            }
        }
        
        // Poll loop - retry until SUCCESS or max retries
        while (retryCount < maxRetries) {
            retryCount++;
            logger.info("Poll attempt {}/{} - Transaction ID: {}", retryCount, maxRetries, transaction.getTransactionId());
            
            try {
                // Poll the status endpoint
                EfashePollStatusResponse pollResponse = efasheApiService.pollTransactionStatus(pollEndpoint);
                
                if (pollResponse != null) {
                    String pollStatus = pollResponse.getStatus();
                    logger.info("EFASHE poll response [{}/{}] - Status: {}, Message: {}, TrxId: {}", 
                        retryCount, maxRetries, pollStatus, pollResponse.getMessage(), pollResponse.getTrxId());
                    
                    // Check if status is SUCCESS (case-insensitive, trim whitespace)
                    String trimmedStatus = pollStatus != null ? pollStatus.trim() : null;
                    if (trimmedStatus != null && "SUCCESS".equalsIgnoreCase(trimmedStatus)) {
                        logger.info("✅ EFASHE transaction SUCCESS confirmed on attempt {}/{}", retryCount, maxRetries);
                        
                        // Set status to SUCCESS
                        transaction.setEfasheStatus("SUCCESS");
                        
                        // For ELECTRICITY service, extract and store token and KWH information
                        if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                            String token = pollResponse.getToken();
                            String pollMessage = pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction completed successfully";
                            String kwh = null;
                            
                            // Extract KWH from extraInfo
                            if (pollResponse.getExtraInfo() != null) {
                                try {
                                    if (pollResponse.getExtraInfo() instanceof java.util.Map) {
                                        @SuppressWarnings("unchecked")
                                        java.util.Map<String, Object> extraInfoMap = (java.util.Map<String, Object>) pollResponse.getExtraInfo();
                                        if (extraInfoMap.containsKey("kwh") || extraInfoMap.containsKey("KWH") || extraInfoMap.containsKey("kWh")) {
                                            Object kwhObj = extraInfoMap.getOrDefault("kwh", extraInfoMap.getOrDefault("KWH", extraInfoMap.get("kWh")));
                                            if (kwhObj != null) {
                                                kwh = kwhObj.toString();
                                                logger.info("ELECTRICITY service - KWH extracted from poll extraInfo: {}", kwh);
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.debug("Error extracting KWH from poll extraInfo: {}", e.getMessage());
                                }
                            }
                            
                            // Build message with token and KWH
                            StringBuilder messageBuilder = new StringBuilder(pollMessage);
                            
                            if (token != null && !token.isEmpty()) {
                                if (messageBuilder.length() > 0) {
                                    messageBuilder.append(" | ");
                                }
                                messageBuilder.append("Token: ").append(token);
                                logger.info("ELECTRICITY service - Token received from poll: {}", token);
                            }
                            
                            if (kwh != null && !kwh.isEmpty()) {
                                if (messageBuilder.length() > 0) {
                                    messageBuilder.append(" | ");
                                }
                                messageBuilder.append("KWH: ").append(kwh);
                                logger.info("ELECTRICITY service - KWH received from poll: {}", kwh);
                            }
                            
                            transaction.setMessage(messageBuilder.toString());
                            
                            // Call /electricity/tokens endpoint to get token and save it
                            try {
                                String meterNumber = transaction.getCustomerAccountNumber();
                                if (meterNumber != null && !meterNumber.trim().isEmpty()) {
                                    logger.info("ELECTRICITY service - Calling /electricity/tokens endpoint for meter: {}", meterNumber);
                                    ElectricityTokensResponse tokensResponse = efasheApiService.getElectricityTokens(meterNumber, 1);
                                    
                                    if (tokensResponse != null && tokensResponse.getData() != null && !tokensResponse.getData().isEmpty()) {
                                        ElectricityTokensResponse.ElectricityTokenData firstTokenData = tokensResponse.getData().get(0);
                                        String firstToken = firstTokenData.getToken();
                                        
                                        if (firstToken != null && !firstToken.trim().isEmpty()) {
                                            // Save the first token to the database
                                            transaction.setToken(firstToken);
                                            logger.info("ELECTRICITY service - Saved first token from /electricity/tokens: {}", firstToken);
                                            
                                            // Update message with the token from tokens endpoint if not already present
                                            if (token == null || token.isEmpty() || !messageBuilder.toString().contains("Token:")) {
                                                if (messageBuilder.length() > 0) {
                                                    messageBuilder.append(" | ");
                                                }
                                                messageBuilder.append("Token: ").append(firstToken);
                                                transaction.setMessage(messageBuilder.toString());
                                                logger.info("ELECTRICITY service - Updated message with token from /electricity/tokens endpoint");
                                            }
                                            
                                            // Also update KWH if available from tokens response
                                            if (firstTokenData.getUnits() != null) {
                                                String unitsStr = String.format("%.1f", firstTokenData.getUnits());
                                                if (!messageBuilder.toString().contains("KWH:")) {
                                                    if (messageBuilder.length() > 0) {
                                                        messageBuilder.append(" | ");
                                                    }
                                                    messageBuilder.append("KWH: ").append(unitsStr);
                                                    transaction.setMessage(messageBuilder.toString());
                                                    logger.info("ELECTRICITY service - Updated message with KWH from /electricity/tokens: {}", unitsStr);
                                                }
                                            }
                                        } else {
                                            logger.warn("ELECTRICITY service - First token from /electricity/tokens is null or empty");
                                        }
                                    } else {
                                        logger.warn("ELECTRICITY service - No token data returned from /electricity/tokens endpoint");
                                    }
                                } else {
                                    logger.warn("ELECTRICITY service - Cannot call /electricity/tokens: meter number is null or empty");
                                }
                            } catch (Exception e) {
                                logger.error("ELECTRICITY service - Error calling /electricity/tokens endpoint: ", e);
                                // Don't fail the transaction if tokens endpoint fails - continue with existing token if available
                            }
                        } else {
                            transaction.setMessage(pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction completed successfully");
                        }
                        
                        // Update transaction
                        efasheTransactionRepository.save(transaction);
                        logger.info("Updated transaction - Transaction ID: {}, EFASHE Status: SUCCESS (from poll attempt {})", 
                            transaction.getTransactionId(), retryCount);
                        
                        // Send WhatsApp notification
                        sendWhatsAppNotification(transaction);
                        
                        logger.info("=== END Automatic Polling (SUCCESS) ===");
                        return true;
                        
                    } else if (trimmedStatus != null && "FAILED".equalsIgnoreCase(trimmedStatus)) {
                        // Transaction failed - stop retrying
                        logger.error("❌ EFASHE transaction FAILED on attempt {}/{} - Status: {}", retryCount, maxRetries, trimmedStatus);
                        transaction.setEfasheStatus("FAILED");
                        transaction.setMessage(pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction failed");
                        transaction.setErrorMessage("EFASHE transaction failed: " + pollResponse.getMessage());
                        efasheTransactionRepository.save(transaction);
                        logger.info("=== END Automatic Polling (FAILED) ===");
                        return false;
                        
                    } else {
                        // Still PENDING or other status - continue polling
                        String actualStatus = trimmedStatus != null ? trimmedStatus : "PENDING";
                        logger.info("EFASHE status is '{}' (not SUCCESS yet) - Will retry in {}s (attempt {}/{})", 
                            actualStatus, retryAfterSecs, retryCount, maxRetries);
                        
                        // Update status (might be PENDING or other intermediate status)
                        transaction.setEfasheStatus(actualStatus);
                        transaction.setMessage(pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction is still processing");
                        efasheTransactionRepository.save(transaction);
                        
                        // Wait before next retry (exponential backoff: increase delay slightly)
                        if (retryCount < maxRetries) {
                            try {
                                int delaySeconds = retryAfterSecs + (retryCount * 2); // Slight increase: 10s, 12s, 14s, etc.
                                logger.info("Waiting {} seconds before next poll attempt...", delaySeconds);
                                Thread.sleep(delaySeconds * 1000L);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                logger.warn("Interrupted while waiting for next poll retry");
                                return false;
                            }
                        }
                    }
                } else {
                    logger.warn("EFASHE poll response is null on attempt {}/{} - Will retry", retryCount, maxRetries);
                    
                    // Wait before retry
                    if (retryCount < maxRetries) {
                        try {
                            int delaySeconds = retryAfterSecs + (retryCount * 2);
                            Thread.sleep(delaySeconds * 1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                }
                
            } catch (Exception e) {
                logger.error("Error polling EFASHE status on attempt {}/{}: {}", retryCount, maxRetries, e.getMessage(), e);
                
                // Wait before retry on error
                if (retryCount < maxRetries) {
                    try {
                        int delaySeconds = retryAfterSecs + (retryCount * 2);
                        logger.info("Error occurred, waiting {} seconds before retry...", delaySeconds);
                        Thread.sleep(delaySeconds * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        
        // Max retries reached - still PENDING
        logger.warn("⚠️ Max retries ({}) reached - EFASHE status is still PENDING. Transaction ID: {}", 
            maxRetries, transaction.getTransactionId());
        transaction.setMessage("EFASHE transaction is still processing after " + maxRetries + " poll attempts");
        efasheTransactionRepository.save(transaction);
        
        logger.info("=== END Automatic Polling (MAX RETRIES REACHED) ===");
        return false;
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
            
            // Log owner name status for ELECTRICITY transactions
            if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                if (ownerName != null && !ownerName.isEmpty()) {
                    logger.info("ELECTRICITY - Owner name found: {}", ownerName);
                } else {
                    logger.warn("ELECTRICITY - Owner name not available in transaction. CustomerAccountName: {}", customerAccountName);
                }
            }
            
            String message;
            
            // For ELECTRICITY service, include token and KWH information if available
            if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                // First, try to get token from database field (saved from /electricity/tokens endpoint)
                String tokenInfo = null;
                String kwhInfo = null;
                
                if (transaction.getToken() != null && !transaction.getToken().trim().isEmpty()) {
                    // Use token from database (saved from /electricity/tokens endpoint)
                    tokenInfo = transaction.getToken().trim();
                    // Format token with dashes every 4 digits
                    tokenInfo = formatTokenWithDashes(tokenInfo);
                    logger.info("ELECTRICITY - Using token from database field: {}", tokenInfo);
                } else if (transaction.getMessage() != null) {
                    // Fall back to extracting token from message if not in database
                    String messageText = transaction.getMessage();
                    logger.info("ELECTRICITY - Token not in database, extracting from message: {}", messageText);
                    
                    // Extract token - try multiple patterns
                    if (messageText.contains("Token:")) {
                        String[] tokenParts = messageText.split("Token:");
                        if (tokenParts.length > 1) {
                            tokenInfo = tokenParts[1].trim();
                            // Remove any additional text after token (KWH, | separator, etc.)
                            if (tokenInfo.contains(" | ")) {
                                tokenInfo = tokenInfo.split(" \\| ")[0].trim();
                            }
                            if (tokenInfo.contains("KWH:")) {
                                tokenInfo = tokenInfo.split("KWH:")[0].trim();
                            }
                            // Clean up any trailing separators
                            tokenInfo = tokenInfo.replaceAll("\\|", "").trim();
                            // Format token with dashes every 4 digits
                            tokenInfo = formatTokenWithDashes(tokenInfo);
                            logger.info("ELECTRICITY - Token extracted and formatted from message: {}", tokenInfo);
                        }
                    } else if (messageText.contains("token:")) {
                        // Try lowercase "token:"
                        String[] tokenParts = messageText.split("token:");
                        if (tokenParts.length > 1) {
                            tokenInfo = tokenParts[1].trim();
                            if (tokenInfo.contains(" | ")) {
                                tokenInfo = tokenInfo.split(" \\| ")[0].trim();
                            }
                            if (tokenInfo.contains("kwh:")) {
                                tokenInfo = tokenInfo.split("kwh:")[0].trim();
                            }
                            tokenInfo = tokenInfo.replaceAll("\\|", "").trim();
                            // Format token with dashes every 4 digits
                            tokenInfo = formatTokenWithDashes(tokenInfo);
                            logger.info("ELECTRICITY - Token extracted and formatted from message (lowercase): {}", tokenInfo);
                        }
                    }
                }
                
                // Extract KWH from message if available
                if (transaction.getMessage() != null) {
                    String messageText = transaction.getMessage();
                    
                    // Extract KWH - try multiple patterns
                    if (messageText.contains("KWH:")) {
                        String[] kwhParts = messageText.split("KWH:");
                        if (kwhParts.length > 1) {
                            String kwhRaw = kwhParts[1].trim();
                            // Remove any additional text after KWH (| separator, etc.)
                            if (kwhRaw.contains(" | ")) {
                                kwhRaw = kwhRaw.split(" \\| ")[0].trim();
                            }
                            // Format KWH to 1 decimal place
                            try {
                                double kwhValue = Double.parseDouble(kwhRaw);
                                kwhInfo = String.format("%.1f", kwhValue);
                                logger.info("ELECTRICITY - KWH extracted and formatted: {} -> {}", kwhRaw, kwhInfo);
                            } catch (NumberFormatException e) {
                                logger.warn("ELECTRICITY - Could not parse KWH value: {}", kwhRaw);
                                kwhInfo = kwhRaw; // Use as-is if parsing fails
                            }
                        }
                    } else if (messageText.contains("kwh:")) {
                        // Try lowercase "kwh:"
                        String[] kwhParts = messageText.split("kwh:");
                        if (kwhParts.length > 1) {
                            String kwhRaw = kwhParts[1].trim();
                            if (kwhRaw.contains(" | ")) {
                                kwhRaw = kwhRaw.split(" \\| ")[0].trim();
                            }
                            try {
                                double kwhValue = Double.parseDouble(kwhRaw);
                                kwhInfo = String.format("%.1f", kwhValue);
                                logger.info("ELECTRICITY - KWH extracted and formatted (lowercase): {} -> {}", kwhRaw, kwhInfo);
                            } catch (NumberFormatException e) {
                                logger.warn("ELECTRICITY - Could not parse KWH value (lowercase): {}", kwhRaw);
                                kwhInfo = kwhRaw;
                            }
                        }
                    }
                }
                
                // Build message with owner name, token, and KWH
                // Always include owner name if available
                String ownerInfo = (ownerName != null && !ownerName.isEmpty()) ? " for " + ownerName : "";
                
                // Ensure token and owner name are always included when available
                if (tokenInfo != null && !tokenInfo.isEmpty()) {
                    // Token is available - include it and KWH in the message
                    if (kwhInfo != null && !kwhInfo.isEmpty()) {
                        // Both token and KWH available - include owner name if available
                        message = String.format(
                            "Bepay-Efashe-%s You Paid %s RWF for %s%s. Your token is: %s. KWH: %s. Cashback: %s RWF. Thanks for using Bepay POCHI App",
                            serviceName.toUpperCase(),
                            amount,
                serviceName,
                            ownerInfo,
                            tokenInfo,
                            kwhInfo,
                            cashbackAmount
                        );
                        logger.info("ELECTRICITY - WhatsApp/SMS message with token, KWH, and owner name: {}", message);
                        logger.info("ELECTRICITY - Token: {}, KWH: {}, Owner: {}", tokenInfo, kwhInfo, ownerName != null ? ownerName : "N/A");
                    } else {
                        // Only token available (KWH not yet available) - include owner name if available
                        message = String.format(
                            "Bepay-Efashe-%s You Paid %s RWF for %s%s. Your token is: %s. Cashback: %s RWF. Thanks for using Bepay POCHI App",
                            serviceName.toUpperCase(),
                amount,
                            serviceName,
                            ownerInfo,
                            tokenInfo,
                cashbackAmount
            );
                        logger.info("ELECTRICITY - WhatsApp/SMS message with token and owner name (KWH not available): {}", message);
                        logger.info("ELECTRICITY - Token: {}, Owner: {}", tokenInfo, ownerName != null ? ownerName : "N/A");
                    }
                } else {
                    // No token available yet - log warning but still include owner name if available
                    logger.warn("ELECTRICITY - Token not found in transaction message. Message: {}", transaction.getMessage());
                    logger.warn("ELECTRICITY - Will send message without token. Owner name: {}", ownerName != null ? ownerName : "N/A");
                    message = String.format(
                        "Bepay-Efashe-%s You Paid %s RWF for %s%s. Cashback: %s RWF. Thanks for using Bepay POCHI App",
                        serviceName.toUpperCase(),
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
                    "Bepay-Efashe-%s You Paid %s RWF for %s%s. Cashback: %s RWF. Thanks for using Bepay POCHI App",
                    serviceName.toUpperCase(),
                    amount,
                    serviceName,
                    ownerInfo,
                    cashbackAmount
                );
            } else {
                // For other services (AIRTIME, TV, MTN)
                message = String.format(
                    "Bepay-Efashe-%s You Paid %s, %s and your cash back is %s, Thanks for using Bepay POCHI App",
                    serviceName.toUpperCase(),
                    serviceName,
                    amount,
                    cashbackAmount
                );
            }
            
            // Use the same phone format as PaymentService (12 digits with 250)
            // WhatsApp service should handle the format conversion if needed
            String whatsappPhone = normalizedPhone;
            
            // Log token and owner name information for electricity transactions
            if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                logger.info("=== ELECTRICITY TRANSACTION NOTIFICATION ===");
                logger.info("Service: ELECTRICITY, Phone: {}, Amount: {} RWF", whatsappPhone, amount);
                if (message.contains("token is:")) {
                    logger.info("✅ TOKEN IS INCLUDED IN MESSAGE - Will be sent via WhatsApp and SMS");
                } else {
                    logger.warn("⚠️ TOKEN NOT FOUND IN MESSAGE - Token may not be available yet");
                }
                if (ownerName != null && !ownerName.isEmpty() && message.contains(" for " + ownerName)) {
                    logger.info("✅ OWNER NAME IS INCLUDED IN MESSAGE: {} - Will be sent via WhatsApp and SMS", ownerName);
                } else if (ownerName == null || ownerName.isEmpty()) {
                    logger.warn("⚠️ OWNER NAME NOT AVAILABLE - Message will be sent without owner name");
                }
            }
            
            logger.info("=== CALLING WhatsApp Service ===");
            logger.info("WhatsApp Phone: {}", whatsappPhone);
            logger.info("WhatsApp Message: {}", message);
            if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                if (message.contains("token is:")) {
                    logger.info("✅ WhatsApp message includes TOKEN for electricity transaction");
                }
                if (ownerName != null && !ownerName.isEmpty() && message.contains(" for " + ownerName)) {
                    logger.info("✅ WhatsApp message includes OWNER NAME: {} for electricity transaction", ownerName);
                }
            }
            
            // Send WhatsApp notification
            whatsAppService.sendWhatsApp(message, whatsappPhone);
            
            logger.info("=== WhatsApp Service Call Completed ===");
            logger.info("WhatsApp notification sent successfully to customer {} for EFASHE transaction: {}", 
                whatsappPhone, transaction.getTransactionId());
            
            // Send SMS notification (same message as WhatsApp) via Swift.com
            logger.info("=== SENDING SMS Notification via Swift.com ===");
            logger.info("SMS Phone: {}", whatsappPhone);
            logger.info("SMS Message: {}", message);
            if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                if (message.contains("token is:")) {
                    logger.info("✅ SMS message (Swift.com) includes TOKEN for electricity transaction");
                }
                if (ownerName != null && !ownerName.isEmpty() && message.contains(" for " + ownerName)) {
                    logger.info("✅ SMS message (Swift.com) includes OWNER NAME: {} for electricity transaction", ownerName);
                }
            }
            try {
                messagingService.sendSms(message, whatsappPhone);
                logger.info("✅ SMS notification sent successfully via Swift.com to customer {} for EFASHE transaction: {}", 
                    whatsappPhone, transaction.getTransactionId());
            } catch (Exception smsException) {
                logger.error("Failed to send SMS notification (WhatsApp was sent successfully): ", smsException);
                // Don't fail the transaction if SMS fails - WhatsApp was already sent
            }
            
            logger.info("=== END WhatsApp and SMS Notification ===");
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
        
        // Check if any filters are applied (excluding normalizedPhone for USER role, as it's always set)
        boolean hasFilters = serviceType != null || fromDate != null || toDate != null 
            || (isAdmin || isReceiver) && normalizedPhone != null && !normalizedPhone.trim().isEmpty();
        
        // Only exclude PENDING transactions when filters are applied
        // When no filtering, show all transactions including PENDING and FAILED
        if (hasFilters) {
            // Exclude PENDING transactions - only show SUCCESS or FAILED
            // Exclude if MoPay status is NULL or PENDING (transaction still in progress)
            queryBuilder.append("AND t.mopayStatus IS NOT NULL AND t.mopayStatus != 'PENDING' ");
        }
        // If no filters, show all transactions (including PENDING)
        
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
        
        // Only exclude PENDING transactions when filters are applied (same logic as main query)
        if (hasFilters) {
            // Exclude PENDING transactions - only show SUCCESS or FAILED
            // Exclude if MoPay status is NULL or PENDING (transaction still in progress)
            countQueryBuilder.append("AND t.mopayStatus IS NOT NULL AND t.mopayStatus != 'PENDING' ");
        }
        // If no filters, show all transactions (including PENDING)
        
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
        
        // Set customer account name (available for all service types)
        response.setCustomerAccountName(transaction.getCustomerAccountName());
        
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
        
        // Extract service-specific information
        if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
            // Extract token and KWH from message for ELECTRICITY
            String message = transaction.getMessage();
            if (message != null) {
                // Extract token (format: "Token: XXXXX" or "token: XXXXX")
                if (message.contains("Token:") || message.contains("token:")) {
                    String[] tokenParts = message.split("(?i)Token:");
                    if (tokenParts.length > 1) {
                        String tokenValue = tokenParts[1].trim();
                        // Remove any additional text after token (e.g., " | " separator)
                        if (tokenValue.contains(" | ")) {
                            tokenValue = tokenValue.split(" \\| ")[0];
                        }
                        response.setToken(tokenValue);
                    }
                }
                
                // Try to extract KWH from message (common patterns: "KWH: XX", "kWh: XX", "XX KWH")
                // KWH might be in the message or execute response
                if (message.toLowerCase().contains("kwh") || message.toLowerCase().contains("kwh")) {
                    // Try to extract KWH value
                    String kwhValue = extractKwhFromMessage(message);
                    if (kwhValue != null && !kwhValue.isEmpty()) {
                        response.setKwh(kwhValue);
                    }
                }
            }
        } else if (transaction.getServiceType() == EfasheServiceType.TV) {
            // For TV, decoder number is the customer account number
            response.setDecoderNumber(transaction.getCustomerAccountNumber());
        }
        
        response.setCreatedAt(transaction.getCreatedAt());
        response.setUpdatedAt(transaction.getUpdatedAt());
        return response;
    }
    
    /**
     * Extract KWH value from message string
     * Looks for patterns like "KWH: 50", "50 KWH", "kWh: 50", etc.
     */
    private String extractKwhFromMessage(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        
        try {
            // Pattern 1: "KWH: XX" or "kWh: XX"
            java.util.regex.Pattern pattern1 = java.util.regex.Pattern.compile("(?i)kwh[\\s:]+(\\d+(?:\\.\\d+)?)");
            java.util.regex.Matcher matcher1 = pattern1.matcher(message);
            if (matcher1.find()) {
                return matcher1.group(1);
            }
            
            // Pattern 2: "XX KWH" or "XX kWh"
            java.util.regex.Pattern pattern2 = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?i)kwh");
            java.util.regex.Matcher matcher2 = pattern2.matcher(message);
            if (matcher2.find()) {
                return matcher2.group(1);
            }
        } catch (Exception e) {
            logger.debug("Error extracting KWH from message: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Format token number by grouping digits into groups of 4 with dashes
     * Example: "1234567890123456" -> "1234-5678-9012-3456"
     * @param token The raw token string (may contain non-digit characters)
     * @return Formatted token with dashes every 4 digits, or original string if formatting fails
     */
    private String formatTokenWithDashes(String token) {
        if (token == null || token.trim().isEmpty()) {
            return token;
        }
        
        // Remove all non-digit characters to get only digits
        String digitsOnly = token.replaceAll("[^0-9]", "");
        
        if (digitsOnly.isEmpty()) {
            // If no digits found, return original token
            return token;
        }
        
        // Group digits into groups of 4 with dashes
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < digitsOnly.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                formatted.append("-");
            }
            formatted.append(digitsOnly.charAt(i));
        }
        
        logger.debug("Token formatted: {} -> {}", token, formatted.toString());
        return formatted.toString();
    }
}

