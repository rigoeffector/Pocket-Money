package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.*;
import com.pocketmoney.pocketmoney.entity.EfasheTransaction;
import com.pocketmoney.pocketmoney.entity.EfasheRefundHistory;
import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import com.pocketmoney.pocketmoney.entity.User;
import com.pocketmoney.pocketmoney.repository.EfasheTransactionRepository;
import com.pocketmoney.pocketmoney.repository.EfasheRefundHistoryRepository;
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
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Service
public class EfashePaymentService {

    private static final Logger logger = LoggerFactory.getLogger(EfashePaymentService.class);

    private final EfasheSettingsService efasheSettingsService;
    private final MoPayService moPayService;
    private final BizaoPaymentService bizaoPaymentService;
    private final EfasheApiService efasheApiService;
    private final EfasheTransactionRepository efasheTransactionRepository;
    private final EfasheRefundHistoryRepository efasheRefundHistoryRepository;
    private final WhatsAppService whatsAppService;
    private final MessagingService messagingService;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    @Value("${bizaopayment.webhook.signing.key:testKey}")
    private String bizaoPaymentWebhookSigningKey;

    public EfashePaymentService(EfasheSettingsService efasheSettingsService, 
                                 MoPayService moPayService,
                                 BizaoPaymentService bizaoPaymentService,
                                 EfasheApiService efasheApiService,
                                 EfasheTransactionRepository efasheTransactionRepository,
                                 EfasheRefundHistoryRepository efasheRefundHistoryRepository,
                                 WhatsAppService whatsAppService,
                                 MessagingService messagingService,
                                 UserRepository userRepository,
                                 EntityManager entityManager) {
        this.efasheSettingsService = efasheSettingsService;
        this.moPayService = moPayService;
        this.bizaoPaymentService = bizaoPaymentService;
        this.efasheApiService = efasheApiService;
        this.efasheTransactionRepository = efasheTransactionRepository;
        this.efasheRefundHistoryRepository = efasheRefundHistoryRepository;
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
        
        // Log full validate response
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            String validateResponseJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(validateResponse);
            logger.info("═══════════════════════════════════════════════════════════════");
            logger.info("📋 EFASHE VALIDATE RESPONSE (Parsed)");
            logger.info("═══════════════════════════════════════════════════════════════");
            logger.info("{}", validateResponseJson);
            logger.info("═══════════════════════════════════════════════════════════════");
        } catch (Exception e) {
            logger.warn("Could not serialize validate response to JSON: {}", e.getMessage());
            logger.info("Validate Response - TrxId: {}, CustomerAccountName: {}, VendMin: {}, VendMax: {}", 
                validateResponse != null ? validateResponse.getTrxId() : "N/A",
                validateResponse != null ? validateResponse.getCustomerAccountName() : "N/A",
                validateResponse != null ? validateResponse.getVendMin() : "N/A",
                validateResponse != null ? validateResponse.getVendMax() : "N/A");
        }
        
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
        
        // For AIRTIME service, try to get customer name from BizaoPayment account holder info if not available from validate
        if ((customerAccountName == null || customerAccountName.trim().isEmpty()) 
            && (request.getServiceType() == EfasheServiceType.AIRTIME || request.getServiceType() == EfasheServiceType.MTN)) {
            try {
                logger.info("AIRTIME service - Customer name not available from validate, fetching from BizaoPayment account holder info for phone: {}", normalizedCustomerPhone);
                com.pocketmoney.pocketmoney.dto.BizaoAccountHolderResponse accountHolderInfo = bizaoPaymentService.getAccountHolderInformation(normalizedCustomerPhone);
                
                if (accountHolderInfo != null && accountHolderInfo.getStatus() != null && accountHolderInfo.getStatus() == 200) {
                    String fullName = accountHolderInfo.getFullName();
                    if (fullName != null && !fullName.trim().isEmpty()) {
                        customerAccountName = fullName.trim();
                        logger.info("✅ Customer name retrieved from BizaoPayment account holder info: {}", customerAccountName);
                    } else {
                        logger.warn("BizaoPayment account holder info returned status 200 but no name available");
                    }
                } else {
                    logger.warn("BizaoPayment account holder info returned non-success status: {}", 
                        accountHolderInfo != null ? accountHolderInfo.getStatus() : "null");
                }
            } catch (Exception e) {
                logger.warn("Failed to get customer name from BizaoPayment account holder info (non-critical): {}", e.getMessage());
                // Don't fail the transaction if account holder info fails
            }
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
        
        // Validate amount against vendMin (minimum allowed amount) - applies to all service types
        if (validateResponse.getVendMin() != null && amount != null) {
            BigDecimal vendMin = BigDecimal.valueOf(validateResponse.getVendMin());
            if (amount.compareTo(vendMin) < 0) {
                String errorMsg = String.format(
                    "Amount %s RWF is below the minimum allowed amount of %s RWF. Please enter an amount of at least %s RWF.",
                    amount.toPlainString(),
                    vendMin.toPlainString(),
                    vendMin.toPlainString()
                );
                logger.error("Amount validation failed - Amount: {}, Minimum allowed (vendMin): {}", amount, vendMin);
                throw new RuntimeException(errorMsg);
            }
            logger.info("Amount validation passed - Amount: {}, Minimum allowed (vendMin): {}", amount, vendMin);
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
        // For RRA: use fixed charge table. For other services: calculate amounts based on percentages
        BigDecimal customerCashbackAmount = BigDecimal.ZERO;
        BigDecimal agentCommissionAmount = BigDecimal.ZERO;
        BigDecimal besoftShareAmount = BigDecimal.ZERO;
        BigDecimal fullAmountPhoneReceives = BigDecimal.ZERO;
        
        if (amount != null) {
            if (request.getServiceType() == EfasheServiceType.RRA) {
                // RRA: Use fixed charge based on amount (customer pays amount + charge, charge goes to besoft)
                BigDecimal rraCharge = getRraCharge(amount);
                customerCashbackAmount = BigDecimal.ZERO;
                agentCommissionAmount = BigDecimal.ZERO;
                besoftShareAmount = rraCharge;
                fullAmountPhoneReceives = amount; // Full amount phone pays RRA
                logger.info("RRA amount breakdown - RRA Amount: {}, Charge: {}, Total to debit: {}",
                    amount, rraCharge, amount.add(rraCharge));
            } else {
                // Other services: Calculate amounts based on percentages
                BigDecimal agentCommissionPercent = settingsResponse.getAgentCommissionPercentage();
                BigDecimal customerCashbackPercent = settingsResponse.getCustomerCashbackPercentage();
                BigDecimal besoftSharePercent = settingsResponse.getBesoftSharePercentage();

                customerCashbackAmount = amount.multiply(customerCashbackPercent)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    .setScale(0, RoundingMode.HALF_UP);
                agentCommissionAmount = amount.multiply(agentCommissionPercent)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    .setScale(0, RoundingMode.HALF_UP);
                besoftShareAmount = amount.multiply(besoftSharePercent)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    .setScale(0, RoundingMode.HALF_UP);
                fullAmountPhoneReceives = amount.subtract(customerCashbackAmount)
                    .subtract(besoftShareAmount)
                    .setScale(0, RoundingMode.HALF_UP);

                logger.info("Amount breakdown - Total: {}, Customer Cashback: {}, Bepay Share: {}, Agent Commission: {}, Full Amount Phone: {}",
                    amount, customerCashbackAmount, besoftShareAmount, agentCommissionAmount, fullAmountPhoneReceives);

                if (fullAmountPhoneReceives.compareTo(BigDecimal.ZERO) < 0) {
                    throw new RuntimeException("Invalid percentage configuration: Total percentages exceed 100%. Full amount phone would receive negative amount: " + fullAmountPhoneReceives);
                }
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
        
        // Log full validate response
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            String validateResponseJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(validateResponse);
            logger.info("═══════════════════════════════════════════════════════════════");
            logger.info("📋 EFASHE VALIDATE RESPONSE (Parsed) - For Other");
            logger.info("═══════════════════════════════════════════════════════════════");
            logger.info("{}", validateResponseJson);
            logger.info("═══════════════════════════════════════════════════════════════");
        } catch (Exception e) {
            logger.warn("Could not serialize validate response to JSON: {}", e.getMessage());
            logger.info("Validate Response - TrxId: {}, CustomerAccountName: {}, VendMin: {}, VendMax: {}", 
                validateResponse != null ? validateResponse.getTrxId() : "N/A",
                validateResponse != null ? validateResponse.getCustomerAccountName() : "N/A",
                validateResponse != null ? validateResponse.getVendMin() : "N/A",
                validateResponse != null ? validateResponse.getVendMax() : "N/A");
        }
        
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

        // For RRA: total to debit = amount (RRA payment) + charge (besoftShareAmount)
        // For other services: total to debit = amount
        BigDecimal totalMoPayAmount = amount;
        if (transaction.getServiceType() == EfasheServiceType.RRA 
                && transaction.getBesoftShareAmount() != null 
                && transaction.getBesoftShareAmount().compareTo(BigDecimal.ZERO) > 0) {
            totalMoPayAmount = amount.add(transaction.getBesoftShareAmount());
            logger.info("RRA payment - Total to debit: {} (RRA amount: {} + charge: {})", 
                totalMoPayAmount, amount, transaction.getBesoftShareAmount());
        }

        // Build MoPay request
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        moPayRequest.setAmount(totalMoPayAmount);
        moPayRequest.setCurrency(transaction.getCurrency());
        moPayRequest.setPhone(normalizedCustomerPhone);
        moPayRequest.setPayment_mode(transaction.getPaymentMode());
        moPayRequest.setMessage(transaction.getMessage());
        moPayRequest.setCallback_url(transaction.getCallbackUrl());
        
        // Build transfers array
        List<MoPayInitiateRequest.Transfer> transfers = new ArrayList<>();

        // Transfer 1: Full Amount Phone Number
        // For RRA: fullAmountPhone receives amount (to pay RRA). For others: amount - cashback - besoftShare
        MoPayInitiateRequest.Transfer transfer1 = new MoPayInitiateRequest.Transfer();
        BigDecimal fullAmountPhoneReceives;
        if (transaction.getServiceType() == EfasheServiceType.RRA) {
            fullAmountPhoneReceives = amount; // Full amount goes to pay RRA
        } else {
            fullAmountPhoneReceives = amount.subtract(
                transaction.getCustomerCashbackAmount() != null ? transaction.getCustomerCashbackAmount() : BigDecimal.ZERO)
                .subtract(transaction.getBesoftShareAmount() != null ? transaction.getBesoftShareAmount() : BigDecimal.ZERO)
                .setScale(0, RoundingMode.HALF_UP);
        }
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
     * Process a validated transaction by calling BizaoPayment
     * Updates validated flag to "PROCESS" and initiates BizaoPayment payment
     * @param transactionId The transaction ID from initiate response
     * @return EfasheInitiateResponse with BizaoPayment response
     */
    @Transactional
    public EfasheInitiateResponse processPaymentWithBizao(String transactionId) {
        logger.info("Processing EFASHE payment with BizaoPayment - Transaction ID: {}", transactionId);
        
        // Find transaction by ID
        EfasheTransaction transaction = efasheTransactionRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new RuntimeException("EFASHE transaction not found: " + transactionId));
        
        // Check if transaction is in INITIAL state
        if (!"INITIAL".equals(transaction.getValidated())) {
            throw new RuntimeException("Transaction is not in INITIAL state. Current state: " + transaction.getValidated());
        }
        
        logger.info("Found transaction in INITIAL state - Updating to PROCESS and initiating BizaoPayment");
        
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

        // For RRA: total to debit = amount (RRA payment) + charge (besoftShareAmount)
        BigDecimal totalBizaoAmount = amount;
        if (transaction.getServiceType() == EfasheServiceType.RRA 
                && transaction.getBesoftShareAmount() != null 
                && transaction.getBesoftShareAmount().compareTo(BigDecimal.ZERO) > 0) {
            totalBizaoAmount = amount.add(transaction.getBesoftShareAmount());
            logger.info("RRA BizaoPayment - Total to debit: {} (RRA amount: {} + charge: {})", 
                totalBizaoAmount, amount, transaction.getBesoftShareAmount());
        }

        // Build BizaoPayment request
        BizaoPaymentInitiateRequest bizaoRequest = new BizaoPaymentInitiateRequest();
        bizaoRequest.setTransactionId(transactionId); // Use the transaction ID
        bizaoRequest.setAccount_no(normalizedCustomerPhone); // DEBIT - customer phone
        bizaoRequest.setTitle("EFASHE " + transaction.getServiceType() + " Payment");
        bizaoRequest.setDetails("Payment for " + transaction.getServiceType() + " service");
        bizaoRequest.setPayment_type("momo");
        bizaoRequest.setAmount(totalBizaoAmount);
        bizaoRequest.setCurrency(transaction.getCurrency() != null ? transaction.getCurrency() : "RWF");
        bizaoRequest.setMessage(transaction.getMessage() != null ? transaction.getMessage() : 
            "EFASHE " + transaction.getServiceType() + " payment");
        
        // Build transfers array
        List<BizaoPaymentInitiateRequest.Transfer> transfers = new ArrayList<>();

        // Transfer 1: Full Amount Phone Number
        // For RRA: fullAmountPhone receives amount (to pay RRA). For others: amount - cashback - besoftShare
        BizaoPaymentInitiateRequest.Transfer transfer1 = new BizaoPaymentInitiateRequest.Transfer();
        BigDecimal fullAmountPhoneReceives;
        if (transaction.getServiceType() == EfasheServiceType.RRA) {
            fullAmountPhoneReceives = amount; // Full amount goes to pay RRA
        } else {
            fullAmountPhoneReceives = amount.subtract(
                transaction.getCustomerCashbackAmount() != null ? transaction.getCustomerCashbackAmount() : BigDecimal.ZERO)
                .subtract(transaction.getBesoftShareAmount() != null ? transaction.getBesoftShareAmount() : BigDecimal.ZERO)
                .setScale(0, RoundingMode.HALF_UP);
        }
        transfer1.setTransactionId(transactionId);
        String normalizedFullAmountPhone = normalizePhoneTo12Digits(transaction.getFullAmountPhone());
        transfer1.setAccount_no(normalizedFullAmountPhone);
        transfer1.setPayment_type("momo");
        transfer1.setAmount(fullAmountPhoneReceives);
        transfer1.setCurrency("RWF");
        transfer1.setMessage("EFASHE " + transaction.getServiceType() + " - Full amount");
        transfers.add(transfer1);
        
        // Transfer 2: Customer Cashback
        if (transaction.getCustomerCashbackAmount() != null && transaction.getCustomerCashbackAmount().compareTo(BigDecimal.ZERO) > 0) {
            BizaoPaymentInitiateRequest.Transfer transfer2 = new BizaoPaymentInitiateRequest.Transfer();
            transfer2.setTransactionId(transactionId);
            transfer2.setAccount_no(normalizedCustomerPhone);
            transfer2.setPayment_type("momo");
            transfer2.setAmount(transaction.getCustomerCashbackAmount());
            transfer2.setCurrency("RWF");
            transfer2.setMessage("EFASHE " + transaction.getServiceType() + " - Customer cashback");
            transfers.add(transfer2);
        }
        
        // Transfer 3: Besoft Share
        if (transaction.getBesoftShareAmount() != null && transaction.getBesoftShareAmount().compareTo(BigDecimal.ZERO) > 0) {
            String normalizedCashbackPhone = normalizePhoneTo12Digits(transaction.getCashbackPhone());
            BizaoPaymentInitiateRequest.Transfer transfer3 = new BizaoPaymentInitiateRequest.Transfer();
            transfer3.setTransactionId(transactionId);
            transfer3.setAccount_no(normalizedCashbackPhone);
            transfer3.setPayment_type("momo");
            transfer3.setAmount(transaction.getBesoftShareAmount());
            transfer3.setCurrency("RWF");
            transfer3.setMessage("EFASHE " + transaction.getServiceType() + " - Besoft share");
            transfers.add(transfer3);
        }

        bizaoRequest.setTransfers(transfers);

        // Initiate payment with BizaoPayment
        BizaoPaymentResponse bizaoResponse = bizaoPaymentService.initiatePayment(bizaoRequest);

        // Check if the response indicates success
        if (bizaoResponse == null) {
            throw new RuntimeException("BizaoPayment returned null response. Payment initiation failed.");
        }
        
        // Check if there was an error
        if (bizaoResponse.getStatus() != null && bizaoResponse.getStatus() >= 400) {
            String errorMsg = bizaoResponse.getMessage() != null ? bizaoResponse.getMessage() : 
                "BizaoPayment returned error status: " + bizaoResponse.getStatus();
            logger.error("❌ BizaoPayment payment initiation failed - Status: {}, Message: {}", 
                bizaoResponse.getStatus(), errorMsg);
            throw new RuntimeException("BizaoPayment payment initiation failed: " + errorMsg);
        }
        
        String bizaoTransactionId = bizaoResponse.getTransactionId();
        
        // If transactionId is still null, check if status indicates success (200/201)
        if ((bizaoTransactionId == null || bizaoTransactionId.isEmpty()) 
            && bizaoResponse.getStatus() != null 
            && (bizaoResponse.getStatus() == 200 || bizaoResponse.getStatus() == 201)) {
            logger.warn("⚠️ BizaoPayment returned success status {} but no transactionId. Response: {}", 
                bizaoResponse.getStatus(), bizaoResponse);
            // Try to generate a transaction ID from the request
            bizaoTransactionId = bizaoRequest.getTransactionId();
            logger.info("Using request transactionId as fallback: {}", bizaoTransactionId);
        }
        
        if (bizaoTransactionId == null || bizaoTransactionId.isEmpty()) {
            logger.error("❌ BizaoPayment did not return a transaction ID. Response status: {}, Success: {}, Message: {}", 
                bizaoResponse.getStatus(), bizaoResponse.getSuccess(), bizaoResponse.getMessage());
            throw new RuntimeException("BizaoPayment did not return a transaction ID. Payment initiation may have failed. Status: " + 
                bizaoResponse.getStatus() + ", Message: " + bizaoResponse.getMessage());
        }
        
        // Update transaction with BizaoPayment response
        transaction.setTransactionId(bizaoTransactionId); // Update with BizaoPayment transaction ID
        transaction.setMopayTransactionId(bizaoTransactionId); // Store in mopayTransactionId field for compatibility
        
        String initialBizaoStatus = bizaoResponse != null && bizaoResponse.getStatus() != null 
            ? bizaoResponse.getStatus().toString() : "PENDING";
        transaction.setInitialMopayStatus(initialBizaoStatus);
        transaction.setMopayStatus(initialBizaoStatus);
        transaction.setCashbackSent(true); // Mark as sent
        
        // Save updated transaction
        efasheTransactionRepository.save(transaction);
        // Explicitly flush to ensure transaction is written to database before returning
        entityManager.flush();
        logger.info("Updated transaction with BizaoPayment response - Transaction ID: {}, BizaoPayment Transaction ID: {}", 
            transactionId, bizaoTransactionId);

        // Build response - include full BizaoPayment response
        EfasheInitiateResponse response = new EfasheInitiateResponse();
        response.setTransactionId(bizaoTransactionId);
        response.setServiceType(transaction.getServiceType());
        response.setAmount(amount);
        response.setCustomerPhone(normalizedCustomerPhone);
        response.setCustomerAccountName(transaction.getCustomerAccountName());
        
        // Include full BizaoPayment response
        response.setBizaoPaymentResponse(bizaoResponse);
        
        // Convert BizaoPaymentResponse to MoPayResponse format for compatibility
        MoPayResponse moPayResponse = new MoPayResponse();
        moPayResponse.setTransactionId(bizaoTransactionId);
        moPayResponse.setStatus(bizaoResponse.getStatus());
        
        // Don't set success to true if statusDesc is PENDING
        // Only set success to true if statusDesc is SUCCESSFUL
        boolean isSuccessful = "SUCCESSFUL".equalsIgnoreCase(bizaoResponse.getStatusDesc());
        moPayResponse.setSuccess(isSuccessful);
        
        // Set message - if there's a parsing error message, keep it, otherwise use statusDesc
        if (bizaoResponse.getMessage() != null && !bizaoResponse.getMessage().isEmpty()) {
            moPayResponse.setMessage(bizaoResponse.getMessage());
        } else if (bizaoResponse.getStatusDesc() != null) {
            moPayResponse.setMessage("BizaoPayment status: " + bizaoResponse.getStatusDesc());
        } else {
            moPayResponse.setMessage("BizaoPayment initiated");
        }
        
        response.setMoPayResponse(moPayResponse);
        
        response.setFullAmountPhone(normalizedFullAmountPhone);
        response.setCashbackPhone(normalizePhoneTo12Digits(transaction.getCashbackPhone()));
        response.setCustomerCashbackAmount(transaction.getCustomerCashbackAmount());
        response.setBesoftShareAmount(transaction.getBesoftShareAmount());
        response.setFullAmountPhoneReceives(fullAmountPhoneReceives);
        response.setValidated("PROCESS");

        logger.info("EFASHE payment initiated with BizaoPayment - Transaction ID: {}, BizaoPayment Transaction ID: {}. Waiting for webhook callback...", 
            transactionId, bizaoTransactionId);

        // No polling - webhook will be called by BizaoPayment when payment status changes
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
            
            // If EFASHE is already FAILED with a pollEndpoint and MoPay is SUCCESS, execute first to deliver service, then poll
            if (isSuccess && "FAILED".equalsIgnoreCase(transaction.getEfasheStatus()) 
                && transaction.getPollEndpoint() != null 
                && !transaction.getPollEndpoint().isEmpty()) {
                
                logger.info("MoPay is SUCCESS and EFASHE is FAILED with pollEndpoint - Executing transaction first to deliver service, then polling - PollEndpoint: {}", 
                    transaction.getPollEndpoint());
                
                // Execute the transaction first to actually deliver the service (airtime, electricity, etc.)
                try {
                        EfasheExecuteRequest executeRequest = new EfasheExecuteRequest();
                    executeRequest.setTrxId(transaction.getTrxId());
                        executeRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                        executeRequest.setAmount(transaction.getAmount());
                        executeRequest.setVerticalId(getVerticalId(transaction.getServiceType()));
                    
                    String deliveryMethodId = transaction.getDeliveryMethodId();
                    if (deliveryMethodId == null || deliveryMethodId.trim().isEmpty()) {
                        deliveryMethodId = "direct_topup";
                    }
                    executeRequest.setDeliveryMethodId(deliveryMethodId);
                    
                    if ("sms".equals(deliveryMethodId)) {
                        String deliverTo = transaction.getDeliverTo();
                        if (deliverTo == null || deliverTo.trim().isEmpty()) {
                            deliverTo = normalizePhoneTo12Digits(transaction.getCustomerPhone());
                        }
                        executeRequest.setDeliverTo(deliverTo);
                    }
                    
                    // Use executeWithRetry to handle 3-minute window errors
                    executeResponse = executeWithRetry(transaction);
                    
                    if (executeResponse != null) {
                        logger.info("✅ EFASHE execute completed - HTTP Status: {}, Message: {}, ServiceType: {}, CustomerAccountNumber: {}", 
                            executeResponse.getHttpStatusCode(), executeResponse.getMessage(), 
                            transaction.getServiceType(), transaction.getCustomerAccountNumber());
                        
                        // Update pollEndpoint if provided
                        if (executeResponse.getPollEndpoint() != null && !executeResponse.getPollEndpoint().isEmpty()) {
                            String cleanPollEndpoint = executeResponse.getPollEndpoint();
                            if (cleanPollEndpoint.endsWith("/")) {
                                cleanPollEndpoint = cleanPollEndpoint.substring(0, cleanPollEndpoint.length() - 1);
                            }
                            transaction.setPollEndpoint(cleanPollEndpoint);
                            transaction.setRetryAfterSecs(executeResponse.getRetryAfterSecs());
                        }
                        
                        // If execute returns 200/202, it's already successful
                        if (executeResponse.getHttpStatusCode() != null && 
                            (executeResponse.getHttpStatusCode() == 200 || executeResponse.getHttpStatusCode() == 202)) {
                            transaction.setEfasheStatus("SUCCESS");
                            transaction.setMessage(executeResponse.getMessage() != null ? executeResponse.getMessage() : 
                                "EFASHE transaction executed successfully");
                            efasheTransactionRepository.save(transaction);
                            logger.info("✅ EFASHE execute returned HTTP {} - Service delivered successfully", 
                                executeResponse.getHttpStatusCode());
                            
                            // Send notification
                            sendWhatsAppNotification(transaction);
                            // Continue to build response at end of method
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error executing EFASHE transaction during retry: ", e);
                    // Continue to polling anyway
                }
                
                // After execute, poll to check status
                Integer retryAfterSecs = transaction.getRetryAfterSecs() != null && transaction.getRetryAfterSecs() > 0 
                    ? transaction.getRetryAfterSecs() : 10;
                
                boolean pollSuccess = pollUntilSuccess(transaction, transaction.getPollEndpoint(), retryAfterSecs);
                
                if (pollSuccess) {
                    logger.info("✅ Retry polling completed - Transaction ID: {}, EFASHE Status: SUCCESS", 
                        transaction.getTransactionId());
                } else {
                    logger.error("❌ Retry polling failed - Transaction ID: {}, Status: {} - NOT sending notifications. Throwing error.", 
                        transaction.getTransactionId(), transaction.getEfasheStatus());
                    throw new RuntimeException("EFASHE polling failed. Status: " + transaction.getEfasheStatus() + ". Transaction ID: " + transaction.getTransactionId());
                }
            } else if (isSuccess && !"SUCCESS".equalsIgnoreCase(transaction.getEfasheStatus())) {
                logger.info("MoPay transaction SUCCESS detected (status: {}, success: {}). Ensuring BOTH validate AND execute happen...", 
                    statusCode, moPayResponse.getSuccess());
                
                try {
                    // Declare variables at method scope so they can be reused in nested blocks
                    String verticalId = getVerticalId(transaction.getServiceType());
                    EfasheValidateRequest validateRequest;
                    EfasheValidateResponse validateResponse;
                    String deliveryMethodId;
                    // executeResponse is already declared at method level (line 969)
                    
                    // Always validate first to ensure we have a valid trxId (even if one exists, re-validate to be sure)
                    String trxId = transaction.getTrxId();
                    boolean needsRevalidation = (trxId == null || trxId.trim().isEmpty());
                    
                    if (needsRevalidation) {
                        logger.info("No trxId found - Validating EFASHE account first before executing...");
                    } else {
                        logger.info("Re-validating EFASHE account to ensure we have a fresh trxId before executing...");
                    }
                    
                    // Always re-validate to get a fresh trxId before executing
                    validateRequest = new EfasheValidateRequest();
                    validateRequest.setVerticalId(verticalId);
                    validateRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                    
                    logger.info("Validating EFASHE account - Vertical: {}, Customer Account: {}", 
                        verticalId, transaction.getCustomerAccountNumber());
                    
                    validateResponse = efasheApiService.validateAccount(validateRequest);
                    
                    if (validateResponse != null && validateResponse.getTrxId() != null && !validateResponse.getTrxId().trim().isEmpty()) {
                        trxId = validateResponse.getTrxId();
                        logger.info("✅ Got trxId from validate: {} - Updating transaction", trxId);
                        
                        // Update transaction with new trxId
                        transaction.setTrxId(trxId);
                        efasheTransactionRepository.save(transaction);
                    } else {
                        logger.error("❌ Validation failed - Cannot get trxId - NOT sending notifications. Throwing error.");
                        transaction.setEfasheStatus("FAILED");
                        transaction.setErrorMessage("Validation failed - Cannot get trxId");
                        efasheTransactionRepository.save(transaction);
                        throw new RuntimeException("Validation failed - Cannot get trxId. Transaction ID: " + transaction.getTransactionId());
                    }
                    
                    // Now execute with the validated trxId
                    logger.info("Executing EFASHE transaction with validated trxId: {}", trxId);
                    
                    // Validate amount is not null
                    if (transaction.getAmount() == null) {
                        logger.error("EFASHE execute - Amount is null for transaction: {}", transaction.getTransactionId());
                        throw new RuntimeException("Amount is required for EFASHE execute but is null in transaction");
                    }
                    
                    // Build execute request
                        EfasheExecuteRequest executeRequest = new EfasheExecuteRequest();
                    executeRequest.setTrxId(trxId);
                        executeRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                        executeRequest.setAmount(transaction.getAmount());
                    executeRequest.setVerticalId(verticalId);
                    
                    deliveryMethodId = transaction.getDeliveryMethodId();
                    if (deliveryMethodId == null || deliveryMethodId.trim().isEmpty()) {
                        deliveryMethodId = "direct_topup";
                    }
                    executeRequest.setDeliveryMethodId(deliveryMethodId);
                    
                    if ("sms".equals(deliveryMethodId)) {
                        String deliverTo = transaction.getDeliverTo();
                        if (deliverTo == null || deliverTo.trim().isEmpty()) {
                            deliverTo = normalizePhoneTo12Digits(transaction.getCustomerPhone());
                        }
                        executeRequest.setDeliverTo(deliverTo);
                    }
                    
                    // Execute the transaction - this MUST succeed for service to be delivered
                    logger.info("Calling EFASHE /vend/execute to deliver service - TrxId: {}, CustomerAccountNumber: {}, Amount: {}", 
                        trxId, transaction.getCustomerAccountNumber(), transaction.getAmount());
                    
                    try {
                        executeResponse = efasheApiService.executeTransaction(executeRequest);
                    } catch (RuntimeException e) {
                        // Check if it's a 3-minute window error - means execute already happened
                        if (e.getMessage() != null && 
                            (e.getMessage().contains("You cannot perform the same transaction within a 3 minute window") ||
                             e.getMessage().contains("3 minute window"))) {
                            logger.warn("⚠️ Execute failed with 3-minute window error - This means execute already happened. " +
                                "Checking poll to confirm service was delivered. Transaction ID: {}", transaction.getTransactionId());
                            
                            // If pollEndpoint exists, poll to confirm
                            if (transaction.getPollEndpoint() != null && !transaction.getPollEndpoint().isEmpty()) {
                                Integer retryAfterSecs = transaction.getRetryAfterSecs() != null && transaction.getRetryAfterSecs() > 0 
                                    ? transaction.getRetryAfterSecs() : 10;
                                
                                boolean pollSuccess = pollUntilSuccess(transaction, transaction.getPollEndpoint(), retryAfterSecs);
                                
                                if (pollSuccess && "SUCCESS".equalsIgnoreCase(transaction.getEfasheStatus())) {
                                    logger.info("✅ Poll confirmed SUCCESS after 3-minute window error - Service was already delivered. Transaction ID: {}", 
                                        transaction.getTransactionId());
                                    // Service was delivered, continue normally
                                    executeResponse = null; // Set to null to skip execute response handling
                                } else {
                                    logger.error("❌ Poll failed after 3-minute window error - Service may not be delivered. Transaction ID: {}", 
                                        transaction.getTransactionId());
                                    transaction.setEfasheStatus("FAILED");
                                    transaction.setErrorMessage("Execute failed with 3-minute window error and polling failed");
                                    efasheTransactionRepository.save(transaction);
                                    throw new RuntimeException("Execute failed with 3-minute window error and polling failed. Transaction ID: " + transaction.getTransactionId());
                                }
                            } else {
                                // No pollEndpoint - cannot verify, mark as FAILED
                                logger.error("❌ Execute failed with 3-minute window error but no pollEndpoint to verify - NOT sending notifications. Throwing error.");
                                transaction.setEfasheStatus("FAILED");
                                transaction.setErrorMessage("Execute failed with 3-minute window error but no pollEndpoint to verify");
                                efasheTransactionRepository.save(transaction);
                                throw new RuntimeException("Execute failed with 3-minute window error but no pollEndpoint to verify. Transaction ID: " + transaction.getTransactionId());
                            }
                        } else {
                            // Different error - re-throw
                            logger.error("❌ Execute failed with unexpected error - NOT sending notifications. Throwing error: ", e);
                            transaction.setEfasheStatus("FAILED");
                            transaction.setErrorMessage("Execute failed: " + e.getMessage());
                            efasheTransactionRepository.save(transaction);
                            throw new RuntimeException("Execute failed: " + e.getMessage() + ". Transaction ID: " + transaction.getTransactionId(), e);
                        }
                    }
                        
                        if (executeResponse != null) {
                            // EFASHE execute returns async response with pollEndpoint
                            // Remove trailing slash if present
                            String pollEndpoint = executeResponse.getPollEndpoint();
                            if (pollEndpoint != null && pollEndpoint.endsWith("/")) {
                                pollEndpoint = pollEndpoint.substring(0, pollEndpoint.length() - 1);
                            }
                            transaction.setPollEndpoint(pollEndpoint);
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
                                
                                // Call /electricity/tokens endpoint to get token and save it (get latest by timestamp)
                                try {
                                    String meterNumber = transaction.getCustomerAccountNumber();
                                    if (meterNumber != null && !meterNumber.trim().isEmpty()) {
                                        logger.info("ELECTRICITY service - Calling /electricity/tokens endpoint for meter: {} (requesting 3 tokens to find latest)", meterNumber);
                                        // Request last 3 tokens to find the latest one by timestamp
                                        ElectricityTokensResponse tokensResponse = efasheApiService.getElectricityTokens(meterNumber, 3);
                                        
                                        if (tokensResponse != null && tokensResponse.getData() != null && !tokensResponse.getData().isEmpty()) {
                                            // Find the latest token by timestamp
                                            ElectricityTokensResponse.ElectricityTokenData latestTokenData = getLatestTokenByTimestamp(tokensResponse.getData());
                                            
                                            if (latestTokenData != null && latestTokenData.getToken() != null && !latestTokenData.getToken().trim().isEmpty()) {
                                                String latestToken = latestTokenData.getToken();
                                                // Save the latest token to the database
                                                transaction.setToken(latestToken);
                                                logger.info("ELECTRICITY service - Saved latest token from /electricity/tokens (execute): {} (timestamp: {})", latestToken, latestTokenData.getTstamp());
                                                
                                                // Always update/replace token in message with latest token
                                                String currentMessage = messageBuilder.toString();
                                                // Remove old token if exists
                                                currentMessage = currentMessage.replaceAll("(?i)Token:\\s*[0-9\\-]+", "");
                                                // Remove any double separators
                                                currentMessage = currentMessage.replaceAll("\\s*\\|\\s*\\|\\s*", " | ");
                                                currentMessage = currentMessage.replaceAll("^\\s*\\|\\s*", "").replaceAll("\\s*\\|\\s*$", "");
                                                messageBuilder = new StringBuilder(currentMessage.trim());
                                                
                                                // Add latest token
                                                if (messageBuilder.length() > 0) {
                                                    messageBuilder.append(" | ");
                                                }
                                                messageBuilder.append("Token: ").append(latestToken);
                                                
                                                // Also update/replace KWH if available from latest token response
                                                if (latestTokenData.getUnits() != null) {
                                                    String unitsStr = String.format("%.1f", latestTokenData.getUnits());
                                                    // Remove old KWH if exists
                                                    String messageStr = messageBuilder.toString();
                                                    messageStr = messageStr.replaceAll("(?i)KWH:\\s*[0-9.]+", "");
                                                    messageStr = messageStr.replaceAll("\\s*\\|\\s*\\|\\s*", " | ");
                                                    messageStr = messageStr.replaceAll("^\\s*\\|\\s*", "").replaceAll("\\s*\\|\\s*$", "");
                                                    messageBuilder = new StringBuilder(messageStr.trim());
                                                    
                                                    // Add latest KWH
                                                    if (messageBuilder.length() > 0) {
                                                        messageBuilder.append(" | ");
                                                    }
                                                    messageBuilder.append("KWH: ").append(unitsStr);
                                                    logger.info("ELECTRICITY service - Updated message with latest KWH from /electricity/tokens (execute): {}", unitsStr);
                                                }
                                                
                                                transaction.setMessage(messageBuilder.toString());
                                                logger.info("ELECTRICITY service - Updated message with latest token and KWH from /electricity/tokens endpoint (execute)");
                                            } else {
                                                logger.warn("ELECTRICITY service - Latest token from /electricity/tokens (execute) is null or empty");
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
                                // Remove trailing slash if present
                                String cleanPollEndpoint = executeResponse.getPollEndpoint();
                                if (cleanPollEndpoint != null && cleanPollEndpoint.endsWith("/")) {
                                    cleanPollEndpoint = cleanPollEndpoint.substring(0, cleanPollEndpoint.length() - 1);
                                }
                                transaction.setPollEndpoint(cleanPollEndpoint);
                                transaction.setRetryAfterSecs(executeResponse.getRetryAfterSecs());
                                            efasheTransactionRepository.save(transaction);
                                            
                                // Automatic retry polling - poll until SUCCESS or max retries (use cleaned endpoint)
                                boolean pollSuccess = pollUntilSuccess(transaction, cleanPollEndpoint, 
                                    executeResponse.getRetryAfterSecs() != null ? executeResponse.getRetryAfterSecs() : 10);
                                
                                if (!pollSuccess) {
                                    logger.error("❌ EFASHE polling failed - Transaction ID: {}, Status: {} - NOT sending notifications. Throwing error.", 
                                        transaction.getTransactionId(), transaction.getEfasheStatus());
                                    throw new RuntimeException("EFASHE polling failed. Status: " + transaction.getEfasheStatus() + ". Transaction ID: " + transaction.getTransactionId());
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
                            // Execute returned null - re-validate and execute again since MoPay is SUCCESS
                            logger.warn("⚠️ EFASHE execute returned null response. MoPay is SUCCESS, so re-validating and executing again. " +
                                "Transaction ID: {}", transaction.getTransactionId());
                            
                            try {
                                // Re-validate to get a new trxId (reuse variables from outer scope)
                                validateRequest = new EfasheValidateRequest();
                                validateRequest.setVerticalId(verticalId);
                                validateRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                                
                                logger.info("Re-validating EFASHE account - Vertical: {}, Customer Account: {}", 
                                    verticalId, transaction.getCustomerAccountNumber());
                                
                                validateResponse = efasheApiService.validateAccount(validateRequest);
                                
                                if (validateResponse != null && validateResponse.getTrxId() != null && !validateResponse.getTrxId().trim().isEmpty()) {
                                    String newTrxId = validateResponse.getTrxId();
                                    logger.info("✅ Got new trxId from re-validation: {} - Updating transaction and executing", newTrxId);
                                    
                                    // Update transaction with new trxId
                                    transaction.setTrxId(newTrxId);
                                    efasheTransactionRepository.save(transaction);
                                    
                                    // Build execute request with new trxId (reuse deliveryMethodId from outer scope)
                                    deliveryMethodId = transaction.getDeliveryMethodId() != null ? transaction.getDeliveryMethodId() : "direct_topup";
                                    EfasheExecuteRequest retryExecuteRequest = new EfasheExecuteRequest();
                                    retryExecuteRequest.setTrxId(newTrxId);
                                    retryExecuteRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                                    retryExecuteRequest.setAmount(transaction.getAmount());
                                    retryExecuteRequest.setVerticalId(verticalId);
                                    retryExecuteRequest.setDeliveryMethodId(deliveryMethodId);
                                    
                                    if ("sms".equals(deliveryMethodId)) {
                                        String deliverTo = transaction.getDeliverTo();
                                        if (deliverTo == null || deliverTo.trim().isEmpty()) {
                                            deliverTo = normalizePhoneTo12Digits(transaction.getCustomerPhone());
                                        }
                                        retryExecuteRequest.setDeliverTo(deliverTo);
                                    }
                                    
                                    // Execute with new trxId
                                    logger.info("Executing EFASHE transaction with new trxId: {}, CustomerAccountNumber: {}, Amount: {}", 
                                        newTrxId, transaction.getCustomerAccountNumber(), transaction.getAmount());
                                    
                                    EfasheExecuteResponse retryExecuteResponse = efasheApiService.executeTransaction(retryExecuteRequest);
                                    
                                    if (retryExecuteResponse != null) {
                                        // Check if execute was successful
                                        Integer httpStatusCode = retryExecuteResponse.getHttpStatusCode();
                                        if (httpStatusCode != null && (httpStatusCode == 200 || httpStatusCode == 202)) {
                                            logger.info("✅ EFASHE /vend/execute completed successfully after re-validation - HTTP Status: {}, Message: {}", 
                                                httpStatusCode, retryExecuteResponse.getMessage());
                                            
                                            // Update transaction with execute response
                                            transaction.setEfasheStatus("SUCCESS");
                                            transaction.setMessage(retryExecuteResponse.getMessage() != null ? retryExecuteResponse.getMessage() : "EFASHE transaction completed successfully");
                                            
                                            // Update pollEndpoint if provided
                                            String pollEndpoint = retryExecuteResponse.getPollEndpoint();
                                            if (pollEndpoint != null && !pollEndpoint.isEmpty()) {
                                                if (pollEndpoint.endsWith("/")) {
                                                    pollEndpoint = pollEndpoint.substring(0, pollEndpoint.length() - 1);
                                                }
                                                transaction.setPollEndpoint(pollEndpoint);
                                                transaction.setRetryAfterSecs(retryExecuteResponse.getRetryAfterSecs());
                                            }
                                            
                                            efasheTransactionRepository.save(transaction);
                                            logger.info("✅ Transaction marked as SUCCESS after re-validation and execute");
                                        } else {
                                            // Execute returned non-success status
                                            logger.error("❌ EFASHE /vend/execute returned non-success status after re-validation: {} - NOT sending notifications. Throwing error.", httpStatusCode);
                            transaction.setEfasheStatus("FAILED");
                                            transaction.setErrorMessage("EFASHE execute returned non-success status after re-validation: " + httpStatusCode);
                                            efasheTransactionRepository.save(transaction);
                                            throw new RuntimeException("EFASHE execute returned non-success status after re-validation: " + httpStatusCode + ". Transaction ID: " + transaction.getTransactionId());
                        }
                    } else {
                                        // Execute still returned null after re-validation
                                        logger.error("❌ EFASHE /vend/execute still returned null after re-validation - NOT sending notifications. Throwing error.");
                        transaction.setEfasheStatus("FAILED");
                                        transaction.setErrorMessage("EFASHE execute returned null response after re-validation");
                                        efasheTransactionRepository.save(transaction);
                                        throw new RuntimeException("EFASHE execute returned null response after re-validation. Service may not be delivered. Transaction ID: " + transaction.getTransactionId());
                                    }
                                } else {
                                    // Re-validation failed
                                    logger.error("❌ Re-validation failed - Cannot get new trxId - NOT sending notifications. Throwing error.");
                                    transaction.setEfasheStatus("FAILED");
                                    transaction.setErrorMessage("Re-validation failed - Cannot get new trxId");
                                    efasheTransactionRepository.save(transaction);
                                    throw new RuntimeException("Re-validation failed - Cannot get new trxId. Transaction ID: " + transaction.getTransactionId());
                                }
                            } catch (Exception revalidateException) {
                                logger.error("❌ Error during re-validation/execute: ", revalidateException);
                                transaction.setEfasheStatus("FAILED");
                                transaction.setErrorMessage("Failed to re-validate and execute: " + revalidateException.getMessage());
                                efasheTransactionRepository.save(transaction);
                                throw new RuntimeException("Failed to re-validate and execute EFASHE transaction: " + revalidateException.getMessage() + ". Transaction ID: " + transaction.getTransactionId(), revalidateException);
                            }
                        }
                        // Execute response handling continues below (no validate error handling needed since validate is done in initiate)
                } catch (Exception e) {
                    transaction.setEfasheStatus("FAILED");
                    transaction.setErrorMessage("Failed to execute EFASHE transaction: " + e.getMessage());
                    logger.error("❌ Error executing EFASHE transaction after MoPay SUCCESS - NOT sending notifications. Throwing error: ", e);
                    efasheTransactionRepository.save(transaction);
                    throw new RuntimeException("Failed to execute EFASHE transaction: " + e.getMessage() + ". Transaction ID: " + transaction.getTransactionId(), e);
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
                        logger.error("❌ Retry polling failed after execute failure - Transaction ID: {}, Status: {} - NOT sending notifications. Throwing error.", 
                            transaction.getTransactionId(), transaction.getEfasheStatus());
                        throw new RuntimeException("EFASHE polling failed after execute failure. Status: " + transaction.getEfasheStatus() + ". Transaction ID: " + transaction.getTransactionId());
                    }
                }
            } else {
                logger.info("MoPay transaction not yet SUCCESS or already processed - Status: {}, EFASHE Status: {}", 
                    statusCode, transaction.getEfasheStatus());
                
                // If we have a pollEndpoint and MoPay is SUCCESS, poll it and execute if SUCCESS
                // This ensures service delivery when polling returns SUCCESS
                if (isSuccess 
                    && transaction.getPollEndpoint() != null 
                    && !transaction.getPollEndpoint().isEmpty()) {
                    
                    logger.info("MoPay is SUCCESS and pollEndpoint exists - Polling and executing if SUCCESS - PollEndpoint: {}", 
                        transaction.getPollEndpoint());
                    
                    Integer retryAfterSecs = transaction.getRetryAfterSecs() != null && transaction.getRetryAfterSecs() > 0 
                        ? transaction.getRetryAfterSecs() : 10;
                    
                    // Poll the endpoint - pollUntilSuccess will call /vend/execute if polling returns SUCCESS
                    boolean pollSuccess = pollUntilSuccess(transaction, transaction.getPollEndpoint(), retryAfterSecs);
                    
                    if (pollSuccess) {
                        logger.info("✅ Polling completed and execute called - Transaction ID: {}, EFASHE Status: SUCCESS", 
                            transaction.getTransactionId());
                            } else {
                        logger.error("❌ Polling failed - Transaction ID: {}, Status: {} - NOT sending notifications. Throwing error.", 
                            transaction.getTransactionId(), transaction.getEfasheStatus());
                        throw new RuntimeException("EFASHE polling failed. Status: " + transaction.getEfasheStatus() + ". Transaction ID: " + transaction.getTransactionId());
                    }
                } else if (transaction.getPollEndpoint() != null 
                    && !transaction.getPollEndpoint().isEmpty()
                    && !isSuccess) {
                    logger.info("PollEndpoint exists but MoPay is not yet SUCCESS - Skipping polling until MoPay succeeds");
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
        
        // For ELECTRICITY transactions, fetch and include electricity tokens response (get latest token)
        if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
            try {
                String meterNumber = transaction.getCustomerAccountNumber();
                if (meterNumber != null && !meterNumber.trim().isEmpty()) {
                    logger.info("ELECTRICITY transaction - Fetching tokens for meter: {} (requesting 3 tokens to find latest)", meterNumber);
                    // Request last 3 tokens to find the latest one by timestamp
                    ElectricityTokensResponse tokensResponse = efasheApiService.getElectricityTokens(meterNumber, 3);
                    
                    if (tokensResponse != null && tokensResponse.getData() != null && !tokensResponse.getData().isEmpty()) {
                        // Find the latest token by timestamp
                        ElectricityTokensResponse.ElectricityTokenData latestTokenData = getLatestTokenByTimestamp(tokensResponse.getData());
                        if (latestTokenData != null) {
                            // Create a new response with only the latest token
                            ElectricityTokensResponse latestTokenResponse = new ElectricityTokensResponse();
                            latestTokenResponse.setStatus(tokensResponse.getStatus());
                            latestTokenResponse.setData(java.util.List.of(latestTokenData));
                            response.setElectricityTokens(latestTokenResponse);
                            logger.info("ELECTRICITY transaction - Latest token added to status response (timestamp: {})", latestTokenData.getTstamp());
                        } else {
                            // Fallback to original response if we can't determine latest
                            response.setElectricityTokens(tokensResponse);
                            logger.warn("ELECTRICITY transaction - Could not determine latest token, using all tokens");
                        }
                    } else {
                        response.setElectricityTokens(tokensResponse != null ? tokensResponse : new ElectricityTokensResponse());
                        logger.warn("ELECTRICITY transaction - No token data returned");
                    }
                } else {
                    logger.warn("ELECTRICITY transaction - Cannot fetch tokens: meter number is null or empty");
                    // Set empty tokens response
                    response.setElectricityTokens(new ElectricityTokensResponse());
                }
            } catch (Exception e) {
                logger.error("ELECTRICITY transaction - Error fetching tokens for status response: ", e);
                // Set empty tokens response on error
                response.setElectricityTokens(new ElectricityTokensResponse());
            }
            
            // CRITICAL VALIDATION: For ELECTRICITY transactions, if efasheStatus is SUCCESS but no tokens are available, mark as FAILED
            if ("SUCCESS".equalsIgnoreCase(transaction.getEfasheStatus())) {
                ElectricityTokensResponse tokensResponse = response.getElectricityTokens();
                boolean hasTokens = tokensResponse != null 
                    && tokensResponse.getData() != null 
                    && !tokensResponse.getData().isEmpty();
                
                if (!hasTokens) {
                    logger.error("❌ ELECTRICITY transaction marked as SUCCESS but no tokens available - Marking as FAILED. Transaction ID: {}", transactionId);
                    
                    // Update transaction status in database
                    transaction.setEfasheStatus("FAILED");
                    transaction.setErrorMessage("Transaction marked as SUCCESS but no electricity tokens were delivered. Service not delivered.");
                    efasheTransactionRepository.save(transaction);
                    entityManager.flush();
                    
                    // Update response status
                    response.setEfasheStatus("FAILED");
                    response.setMessage("Transaction failed: No electricity tokens were delivered");
                    
                    logger.warn("⚠️ ELECTRICITY transaction status updated to FAILED - Transaction ID: {}, Reason: No tokens delivered", transactionId);
                } else {
                    logger.info("✅ ELECTRICITY transaction validated - Has tokens: true, Status: SUCCESS, Transaction ID: {}", transactionId);
                }
            }
        }
        
        logger.info("Transaction status check result - Transaction ID: {}, MoPay Status: {}, EFASHE Status: {}, Transfers: {}", 
            transactionId, transaction.getMopayStatus(), response.getEfasheStatus(), transfers.size());
        return response;
    }

    /**
     * Check the status of an EFASHE transaction using BizaoPayment transaction ID
     * If status is SUCCESS (200), automatically triggers EFASHE validate and execute
     * @param transactionId Can be either the EFASHE transaction ID or BizaoPayment transaction ID
     * @return EfasheStatusResponse containing the transaction status and EFASHE responses
     */
    @Transactional
    public EfasheStatusResponse checkTransactionStatusWithBizao(String transactionId) {
        logger.info("Checking EFASHE transaction status with BizaoPayment for transaction ID: {} (trying both EFASHE and BizaoPayment IDs)", transactionId);
        
        // Use the helper method to find transaction
        Optional<EfasheTransaction> transactionOpt = findTransactionById(transactionId);
        
        // Get stored transaction record - ALWAYS update existing row, never create new one
        EfasheTransaction transaction = transactionOpt
            .orElseThrow(() -> new RuntimeException("EFASHE transaction not found with ID: " + transactionId + " (tried both EFASHE and BizaoPayment IDs)"));
        
        logger.info("Found existing transaction - ID: {}, EFASHE Transaction ID: {}, BizaoPayment Transaction ID: {}, Current Status: {}, Current EFASHE Status: {}", 
            transaction.getId(), transaction.getTransactionId(), transaction.getMopayTransactionId(), 
            transaction.getMopayStatus(), transaction.getEfasheStatus());
        
        // Use the actual BizaoPayment transaction ID from the transaction record for checking status
        String bizaoTransactionIdToCheck = transaction.getMopayTransactionId() != null 
            ? transaction.getMopayTransactionId() 
            : transaction.getTransactionId(); // Fallback to transactionId if mopayTransactionId is null
        
        logger.info("Checking BizaoPayment status using transaction ID: {} (from stored transaction record)", bizaoTransactionIdToCheck);
        BizaoPaymentResponse bizaoResponse = bizaoPaymentService.checkTransactionStatus(bizaoTransactionIdToCheck);
        
        EfasheExecuteResponse executeResponse = null;
        
        // Update BizaoPayment status in transaction record (UPDATE existing row, don't create new)
        if (bizaoResponse != null && bizaoResponse.getStatus() != null) {
            Integer statusCode = bizaoResponse.getStatus();
            // Only update current status, keep initial status unchanged
            transaction.setMopayStatus(statusCode.toString());
            transaction.setMopayTransactionId(bizaoResponse.getTransactionId());
            
            // Ensure initial status is set if it wasn't set before (for backward compatibility)
            if (transaction.getInitialMopayStatus() == null) {
                transaction.setInitialMopayStatus(transaction.getMopayStatus());
            }
            if (transaction.getInitialEfasheStatus() == null) {
                transaction.setInitialEfasheStatus(transaction.getEfasheStatus() != null ? transaction.getEfasheStatus() : "PENDING");
            }
            
            logger.info("BizaoPayment status check - Status: {}, Success: {}, Transaction ID: {}", 
                statusCode, bizaoResponse.getSuccess(), bizaoResponse.getTransactionId());
            
            // If BizaoPayment status is SUCCESS (200 or 201) OR success flag is true, trigger EFASHE execute
            boolean isSuccess = (statusCode != null && (statusCode == 200 || statusCode == 201)) 
                || (bizaoResponse.getSuccess() != null && bizaoResponse.getSuccess());
            
            if (isSuccess && !"SUCCESS".equalsIgnoreCase(transaction.getEfasheStatus())) {
                logger.info("BizaoPayment transaction SUCCESS detected (status: {}, success: {}). Triggering EFASHE execute...", 
                    statusCode, bizaoResponse.getSuccess());
                
                // Execute EFASHE transaction
                try {
                    EfasheExecuteRequest executeRequest = new EfasheExecuteRequest();
                    executeRequest.setTrxId(transaction.getTrxId());
                    executeRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                    executeRequest.setAmount(transaction.getAmount());
                    executeRequest.setVerticalId(getVerticalId(transaction.getServiceType()));
                    
                    String deliveryMethodId = transaction.getDeliveryMethodId();
                    if (deliveryMethodId == null || deliveryMethodId.trim().isEmpty()) {
                        deliveryMethodId = "direct_topup";
                    }
                    executeRequest.setDeliveryMethodId(deliveryMethodId);
                    
                    if ("sms".equals(deliveryMethodId)) {
                        executeRequest.setDeliverTo(transaction.getDeliverTo());
                    }
                    
                    executeResponse = efasheApiService.executeTransaction(executeRequest);
                    
                    // Log full execute response
                    if (executeResponse != null) {
                        try {
                            ObjectMapper objectMapper = new ObjectMapper();
                            String executeResponseJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(executeResponse);
                            logger.info("═══════════════════════════════════════════════════════════════");
                            logger.info("📋 EFASHE EXECUTE RESPONSE (Parsed)");
                            logger.info("═══════════════════════════════════════════════════════════════");
                            logger.info("{}", executeResponseJson);
                            logger.info("═══════════════════════════════════════════════════════════════");
                        } catch (Exception e) {
                            logger.warn("Could not serialize execute response to JSON: {}", e.getMessage());
                            logger.info("Execute Response - HttpStatusCode: {}, PollEndpoint: {}, RetryAfterSecs: {}, Message: {}", 
                                executeResponse.getHttpStatusCode(),
                                executeResponse.getPollEndpoint(),
                                executeResponse.getRetryAfterSecs(),
                                executeResponse.getMessage());
                        }
                    }
                    
                    if (executeResponse != null) {
                        // Update transaction with execute response
                        if (executeResponse.getPollEndpoint() != null && !executeResponse.getPollEndpoint().isEmpty()) {
                            String cleanPollEndpoint = executeResponse.getPollEndpoint();
                            if (cleanPollEndpoint.endsWith("/")) {
                                cleanPollEndpoint = cleanPollEndpoint.substring(0, cleanPollEndpoint.length() - 1);
                            }
                            transaction.setPollEndpoint(cleanPollEndpoint);
                            transaction.setRetryAfterSecs(executeResponse.getRetryAfterSecs());
                        }
                        
                        // If /vend/execute returns HTTP 200 or 202, set status to SUCCESS immediately
                        if (executeResponse.getHttpStatusCode() != null && 
                            (executeResponse.getHttpStatusCode() == 200 || executeResponse.getHttpStatusCode() == 202)) {
                            transaction.setEfasheStatus("SUCCESS");
                            transaction.setMessage(executeResponse.getMessage() != null ? executeResponse.getMessage() : 
                                "EFASHE transaction executed successfully");
                            efasheTransactionRepository.save(transaction);
                            logger.info("✅ EFASHE execute returned HTTP {} - Service delivered successfully", 
                                executeResponse.getHttpStatusCode());
                            
                            // Send notification
                            sendWhatsAppNotification(transaction);
                        } else if (executeResponse.getPollEndpoint() != null && !executeResponse.getPollEndpoint().isEmpty()) {
                            // If pollEndpoint is provided, poll for status
                            transaction.setEfasheStatus("PENDING");
                            transaction.setMessage("EFASHE transaction initiated. Poll endpoint: " + executeResponse.getPollEndpoint());
                            efasheTransactionRepository.save(transaction);
                            
                            Integer retryAfterSecs = transaction.getRetryAfterSecs() != null && transaction.getRetryAfterSecs() > 0 
                                ? transaction.getRetryAfterSecs() : 10;
                            
                            boolean pollSuccess = pollUntilSuccess(transaction, transaction.getPollEndpoint(), retryAfterSecs);
                            
                            if (!pollSuccess) {
                                logger.warn("EFASHE polling completed but status is still not SUCCESS - Transaction ID: {}, Status: {}", 
                                    transaction.getTransactionId(), transaction.getEfasheStatus());
                            }
                        } else {
                            // Synchronous response
                            String efasheStatus = executeResponse.getStatus() != null ? executeResponse.getStatus() : "SUCCESS";
                            transaction.setEfasheStatus(efasheStatus);
                            transaction.setMessage(executeResponse.getMessage() != null ? executeResponse.getMessage() : "EFASHE transaction executed successfully");
                            efasheTransactionRepository.save(transaction);
                            
                            if ("SUCCESS".equalsIgnoreCase(efasheStatus)) {
                                sendWhatsAppNotification(transaction);
                            }
                        }
                    } else {
                        // Execute returned null - check if we can poll instead
                        if (transaction.getPollEndpoint() != null && !transaction.getPollEndpoint().isEmpty()) {
                            logger.warn("⚠️ EFASHE execute returned null response, but pollEndpoint exists. Attempting to poll status instead. " +
                                "Transaction ID: {}, PollEndpoint: {}", transaction.getTransactionId(), transaction.getPollEndpoint());
                            
                            // Try polling to check if service was already delivered
                            Integer retryAfterSecs = transaction.getRetryAfterSecs() != null && transaction.getRetryAfterSecs() > 0 
                                ? transaction.getRetryAfterSecs() : 10;
                            
                            boolean pollSuccess = pollUntilSuccess(transaction, transaction.getPollEndpoint(), retryAfterSecs);
                            
                            if (pollSuccess && "SUCCESS".equalsIgnoreCase(transaction.getEfasheStatus())) {
                                logger.info("✅ Polling succeeded after execute returned null - Service was already delivered. Transaction ID: {}", 
                                    transaction.getTransactionId());
                                // Service was delivered, continue normally
                            } else {
                                transaction.setEfasheStatus("FAILED");
                                transaction.setErrorMessage("EFASHE execute returned null response and polling failed");
                                efasheTransactionRepository.save(transaction);
                                logger.error("❌ EFASHE execute returned null response and polling failed - Transaction ID: {}", 
                                    transaction.getTransactionId());
                            }
                        } else {
                            transaction.setEfasheStatus("FAILED");
                            transaction.setErrorMessage("EFASHE execute returned null response");
                            efasheTransactionRepository.save(transaction);
                            logger.error("❌ EFASHE execute returned null response and no pollEndpoint available - Transaction ID: {}. Check logs for detailed error information.", 
                                transaction.getTransactionId());
                        }
                    }
                } catch (Exception e) {
                    transaction.setEfasheStatus("FAILED");
                    transaction.setErrorMessage("Failed to execute EFASHE transaction: " + e.getMessage());
                    efasheTransactionRepository.save(transaction);
                    logger.error("Error executing EFASHE transaction after BizaoPayment SUCCESS: ", e);
                }
            }
        }
        
        // Update existing transaction (never create new row)
        efasheTransactionRepository.save(transaction);
        logger.info("Updated existing transaction row - Transaction ID: {}, BizaoPayment Status: {} (Initial: {}), EFASHE Status: {} (Initial: {}), Cashback Sent: {}", 
            transaction.getTransactionId(), transaction.getMopayStatus(), transaction.getInitialMopayStatus(),
            transaction.getEfasheStatus(), transaction.getInitialEfasheStatus(), transaction.getCashbackSent());
        
        // Build response with all information
        EfasheStatusResponse response = new EfasheStatusResponse();
        
        // Convert BizaoPaymentResponse to MoPayResponse format for compatibility
        MoPayResponse moPayResponse = new MoPayResponse();
        if (bizaoResponse != null) {
            moPayResponse.setTransactionId(bizaoResponse.getTransactionId());
            moPayResponse.setStatus(bizaoResponse.getStatus());
            moPayResponse.setSuccess(bizaoResponse.getSuccess());
            moPayResponse.setMessage(bizaoResponse.getMessage());
        }
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
        if (transaction.getFullAmountPhone() != null && transaction.getAmount() != null) {
            EfasheStatusResponse.TransferInfo fullAmountTransfer = new EfasheStatusResponse.TransferInfo();
            fullAmountTransfer.setType("FULL_AMOUNT");
            fullAmountTransfer.setFromPhone(transaction.getCustomerPhone());
            fullAmountTransfer.setToPhone(transaction.getFullAmountPhone());
            BigDecimal fullAmount = transaction.getAmount()
                .subtract(transaction.getCustomerCashbackAmount() != null ? transaction.getCustomerCashbackAmount() : BigDecimal.ZERO)
                .subtract(transaction.getBesoftShareAmount() != null ? transaction.getBesoftShareAmount() : BigDecimal.ZERO);
            fullAmountTransfer.setAmount(fullAmount);
            fullAmountTransfer.setMessage("EFASHE " + transaction.getServiceType() + " - Full amount");
            fullAmountTransfer.setTransactionId(transactionId);
            transfers.add(fullAmountTransfer);
        }
        
        // Transfer 2: Customer Cashback
        if (transaction.getCustomerCashbackAmount() != null 
            && transaction.getCustomerCashbackAmount().compareTo(BigDecimal.ZERO) > 0) {
            EfasheStatusResponse.TransferInfo customerCashbackTransfer = new EfasheStatusResponse.TransferInfo();
            customerCashbackTransfer.setType("CUSTOMER_CASHBACK");
            customerCashbackTransfer.setFromPhone(transaction.getFullAmountPhone());
            customerCashbackTransfer.setToPhone(transaction.getCustomerPhone());
            customerCashbackTransfer.setAmount(transaction.getCustomerCashbackAmount());
            customerCashbackTransfer.setMessage("EFASHE " + transaction.getServiceType() + " - Customer cashback");
            customerCashbackTransfer.setTransactionId(transactionId);
            transfers.add(customerCashbackTransfer);
        }
        
        // Transfer 3: Besoft Share
        if (transaction.getBesoftShareAmount() != null 
            && transaction.getBesoftShareAmount().compareTo(BigDecimal.ZERO) > 0) {
            EfasheStatusResponse.TransferInfo besoftShareTransfer = new EfasheStatusResponse.TransferInfo();
            besoftShareTransfer.setType("BESOFT_SHARE");
            besoftShareTransfer.setFromPhone(transaction.getFullAmountPhone());
            besoftShareTransfer.setToPhone(transaction.getCashbackPhone());
            besoftShareTransfer.setAmount(transaction.getBesoftShareAmount());
            besoftShareTransfer.setMessage("EFASHE " + transaction.getServiceType() + " - Besoft share");
            besoftShareTransfer.setTransactionId(transactionId);
            transfers.add(besoftShareTransfer);
        }
        
        response.setTransfers(transfers);
        
        // For ELECTRICITY transactions, fetch and include electricity tokens response
        if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
            try {
                String meterNumber = transaction.getCustomerAccountNumber();
                if (meterNumber != null && !meterNumber.trim().isEmpty()) {
                    logger.info("ELECTRICITY transaction - Fetching tokens for meter: {} (requesting 3 tokens to find latest)", meterNumber);
                    ElectricityTokensResponse tokensResponse = efasheApiService.getElectricityTokens(meterNumber, 3);
                    
                    if (tokensResponse != null && tokensResponse.getData() != null && !tokensResponse.getData().isEmpty()) {
                        ElectricityTokensResponse.ElectricityTokenData latestTokenData = getLatestTokenByTimestamp(tokensResponse.getData());
                        if (latestTokenData != null) {
                            ElectricityTokensResponse latestTokenResponse = new ElectricityTokensResponse();
                            latestTokenResponse.setStatus(tokensResponse.getStatus());
                            latestTokenResponse.setData(java.util.List.of(latestTokenData));
                            response.setElectricityTokens(latestTokenResponse);
                        } else {
                            response.setElectricityTokens(tokensResponse);
                        }
                    } else {
                        response.setElectricityTokens(tokensResponse != null ? tokensResponse : new ElectricityTokensResponse());
                        logger.warn("ELECTRICITY transaction - No token data returned");
                    }
                } else {
                    logger.warn("ELECTRICITY transaction - Cannot fetch tokens: meter number is null or empty");
                    response.setElectricityTokens(new ElectricityTokensResponse());
                }
            } catch (Exception e) {
                logger.error("ELECTRICITY transaction - Error fetching tokens for status response: ", e);
                response.setElectricityTokens(new ElectricityTokensResponse());
            }
            
            // CRITICAL VALIDATION: For ELECTRICITY transactions, if efasheStatus is SUCCESS but no tokens are available, mark as FAILED
            if ("SUCCESS".equalsIgnoreCase(transaction.getEfasheStatus())) {
                ElectricityTokensResponse tokensResponse = response.getElectricityTokens();
                boolean hasTokens = tokensResponse != null 
                    && tokensResponse.getData() != null 
                    && !tokensResponse.getData().isEmpty();
                
                if (!hasTokens) {
                    logger.error("❌ ELECTRICITY transaction marked as SUCCESS but no tokens available - Marking as FAILED. Transaction ID: {}", transactionId);
                    
                    // Update transaction status in database
                    transaction.setEfasheStatus("FAILED");
                    transaction.setErrorMessage("Transaction marked as SUCCESS but no electricity tokens were delivered. Service not delivered.");
                    efasheTransactionRepository.save(transaction);
                    entityManager.flush();
                    
                    // Update response status
                    response.setEfasheStatus("FAILED");
                    response.setMessage("Transaction failed: No electricity tokens were delivered");
                    
                    logger.warn("⚠️ ELECTRICITY transaction status updated to FAILED - Transaction ID: {}, Reason: No tokens delivered", transactionId);
                    logger.warn("⚠️ NOT SENDING WhatsApp/SMS notifications - Transaction marked as FAILED due to no tokens");
                } else {
                    logger.info("✅ ELECTRICITY transaction validated - Has tokens: true, Status: SUCCESS, Transaction ID: {}", transactionId);
                }
            }
        }
        
        logger.info("Transaction status check result with BizaoPayment - Transaction ID: {}, BizaoPayment Status: {}, EFASHE Status: {}, Transfers: {}", 
            transactionId, transaction.getMopayStatus(), response.getEfasheStatus(), transfers.size());
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
     * Process refund when:
     * 1. EFASHE transaction FAILED but MoPay is SUCCESS (customer paid but service failed)
     * 2. EFASHE transaction SUCCESS but MoPay is FAILED (service succeeded but payment failed)
     * Refunds the full amount to customer without deducting anything
     */
    private void processRefund(EfasheTransaction transaction, String refundReason) {
        logger.info("=== processRefund START ===");
        logger.info("Transaction ID: {}, Amount: {}, Customer Phone: {}", 
            transaction.getTransactionId(), transaction.getAmount(), transaction.getCustomerPhone());
        
        if (transaction.getAmount() == null || transaction.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("Cannot process refund - transaction amount is null or zero: {}", transaction.getAmount());
            return;
        }
        
        if (transaction.getCustomerPhone() == null || transaction.getCustomerPhone().trim().isEmpty()) {
            logger.warn("Cannot process refund - customer phone is null or empty");
            return;
        }
        
        if (transaction.getFullAmountPhone() == null || transaction.getFullAmountPhone().trim().isEmpty()) {
            logger.warn("Cannot process refund - full amount phone is null or empty");
            return;
        }
        
        try {
            // Normalize phone numbers
            String normalizedCustomerPhone = normalizePhoneTo12Digits(transaction.getCustomerPhone());
            String normalizedFullAmountPhone = normalizePhoneTo12Digits(transaction.getFullAmountPhone());
            
            // Full refund amount - no deductions
            BigDecimal refundAmount = transaction.getAmount();
            
            logger.info("Processing refund - Amount: {}, From: {}, To: {}", 
                refundAmount, normalizedFullAmountPhone, normalizedCustomerPhone);
            
            // Create refund transfer using sendCashbackTransfer method
            // Generate unique transaction ID with timestamp to avoid 409 conflicts
            String timestamp = String.valueOf(System.currentTimeMillis());
            String refundTransactionId = transaction.getTransactionId() + "-REFUND-" + timestamp;
            
            // Initiate refund transfer and verify it succeeds
            boolean refundTransferSuccess = false;
            try {
                // Build MoPay request for refund transfer (same as cashback transfer)
                MoPayInitiateRequest refundRequest = new MoPayInitiateRequest();
                refundRequest.setTransaction_id(refundTransactionId);
                refundRequest.setAmount(refundAmount);
                refundRequest.setCurrency("RWF");
                refundRequest.setPhone(normalizedFullAmountPhone); // From: full amount phone
                refundRequest.setPayment_mode("MOBILE");
                refundRequest.setMessage("EFASHE " + transaction.getServiceType() + " - Refund (Transaction Failed)");
                
                // Create transfer to customer
                List<MoPayInitiateRequest.Transfer> transfers = new ArrayList<>();
                MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
                transfer.setAmount(refundAmount);
                Long toPhoneLong = Long.parseLong(normalizedCustomerPhone);
                transfer.setPhone(toPhoneLong); // To: customer phone
                transfer.setMessage("EFASHE " + transaction.getServiceType() + " - Refund (Transaction Failed)");
                transfers.add(transfer);
                refundRequest.setTransfers(transfers);
                
                logger.info("Initiating refund transfer - Transaction ID: {}, Amount: {}, From: {}, To: {}", 
                    refundTransactionId, refundAmount, normalizedFullAmountPhone, normalizedCustomerPhone);
                
                // Call MoPay to initiate the refund transfer
                MoPayResponse refundResponse = moPayService.initiatePayment(refundRequest);
                
                // Check if refund was successful: status 201 (CREATED) or 200 (OK) OR success flag is true
                // This matches how we check MoPay success elsewhere in the codebase
                boolean isRefundSuccess = false;
                if (refundResponse != null) {
                    Integer statusCode = refundResponse.getStatus();
                    // Status 201 (CREATED) or 200 (OK) means success, OR success flag is true
                    isRefundSuccess = (statusCode != null && (statusCode == 200 || statusCode == 201))
                        || (refundResponse.getSuccess() != null && refundResponse.getSuccess())
                        || (refundResponse.getTransactionId() != null && !refundResponse.getTransactionId().isEmpty());
                }
                
                if (isRefundSuccess) {
                    refundTransferSuccess = true;
                    logger.info("✅ Refund transfer initiated successfully - Transaction ID: {}, MoPay Status: {}, MoPay Transaction ID: {}", 
                        refundTransactionId, refundResponse.getStatus(), refundResponse.getTransactionId());
                } else {
                    logger.error("❌ Refund transfer failed - Transaction ID: {}, Response: {}", 
                        refundTransactionId, refundResponse);
                    throw new RuntimeException("Refund transfer failed. MoPay response: " + 
                        (refundResponse != null ? refundResponse.toString() : "null"));
                }
            } catch (Exception transferException) {
                logger.error("Error initiating refund transfer: ", transferException);
                throw new RuntimeException("Failed to initiate refund transfer: " + transferException.getMessage(), transferException);
            }
            
            // Only mark as processed if transfer was successful
            if (refundTransferSuccess) {
                // Mark refund as processed in error message
                String existingErrorMessage = transaction.getErrorMessage() != null ? transaction.getErrorMessage() : "";
                transaction.setErrorMessage(existingErrorMessage + " | REFUND_PROCESSED");
                
                // Keep original MoPay status (don't change to REFUNDED)
                logger.info("Refund processed successfully for transaction: {} - MoPay status remains: {}", 
                    transaction.getTransactionId(), transaction.getMopayStatus());
            }
            
            // Send SMS and WhatsApp notifications about refund
            try {
                String serviceName = transaction.getServiceType() != null ? transaction.getServiceType().toString() : "payment";
                String amountStr = refundAmount.toPlainString();
                
                // Build refund message based on reason
                String refundMessage;
                if (refundReason.contains("EFASHE FAILED")) {
                    refundMessage = "The service failed but your payment was successful.";
                } else if (refundReason.contains("MoPay FAILED")) {
                    refundMessage = "The service succeeded but payment processing failed.";
                } else {
                    refundMessage = "Transaction processing issue occurred.";
                }
                
                // SMS message
                String smsMessage = String.format(
                    "Bepay-Efashe-%s Refund: Your payment of %s RWF for %s has been refunded. Transaction ID: %s. %s Thanks for using Bepay POCHI App",
                    serviceName.toUpperCase(),
                    amountStr,
                    serviceName,
                    transaction.getTransactionId(),
                    refundMessage
                );
                
                // WhatsApp message
                String whatsAppMessage = String.format(
                    "Bepay-Efashe-%s Refund: Your payment of %s RWF for %s has been refunded. Transaction ID: %s. %s Thanks for using Bepay POCHI App",
                    serviceName.toUpperCase(),
                    amountStr,
                    serviceName,
                    transaction.getTransactionId(),
                    refundMessage
                );
                
                messagingService.sendSms(smsMessage, normalizedCustomerPhone);
                whatsAppService.sendWhatsApp(whatsAppMessage, normalizedCustomerPhone);
                
                logger.info("✅ Refund notifications sent - SMS and WhatsApp sent to customer: {}", normalizedCustomerPhone);
            } catch (Exception notificationException) {
                logger.error("Error sending refund notifications: ", notificationException);
                // Don't fail refund if notifications fail
            }
            
            logger.info("✅ Refund processed successfully - Amount: {}, To: {}", refundAmount, normalizedCustomerPhone);
            logger.info("=== processRefund END ===");
        } catch (Exception e) {
            logger.error("=== ERROR in processRefund ===");
            logger.error("Transaction ID: {}, Amount: {}, Customer Phone: {}", 
                transaction.getTransactionId(), transaction.getAmount(), transaction.getCustomerPhone());
            logger.error("Error processing refund: ", e);
            e.printStackTrace();
            logger.error("=== END ERROR ===");
            throw e;
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
     * Calculate RRA charge based on payment amount (FRW).
     * Pricing table:
     * 1-1,000: 160 | 1,001-10,000: 300 | 10,001-40,000: 500 | 40,001-75,000: 1,000
     * 75,001-150,000: 1,500 | 150,001-500,000: 2,000 | 500,001-1,000,000: 3,000
     * 1,000,001-5,000,000: 5,000 | 5,000,001+: 10,000
     */
    private BigDecimal getRraCharge(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        long amountLong = amount.longValue();
        if (amountLong <= 1000) return BigDecimal.valueOf(160);
        if (amountLong <= 10000) return BigDecimal.valueOf(300);
        if (amountLong <= 40000) return BigDecimal.valueOf(500);
        if (amountLong <= 75000) return BigDecimal.valueOf(1000);
        if (amountLong <= 150000) return BigDecimal.valueOf(1500);
        if (amountLong <= 500000) return BigDecimal.valueOf(2000);
        if (amountLong <= 1000000) return BigDecimal.valueOf(3000);
        if (amountLong <= 5000000) return BigDecimal.valueOf(5000);
        return BigDecimal.valueOf(10000);
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
    /**
     * Execute EFASHE transaction with retry logic for 3-minute window errors
     * If execute fails with "3 minute window" error, re-validates to get new trxId and retries
     * 
     * @param transaction The EFASHE transaction
     * @return EfasheExecuteResponse if successful, null otherwise
     */
    private EfasheExecuteResponse executeWithRetry(EfasheTransaction transaction) {
        try {
            EfasheExecuteRequest executeRequest = new EfasheExecuteRequest();
            executeRequest.setTrxId(transaction.getTrxId());
            executeRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
            executeRequest.setAmount(transaction.getAmount());
            executeRequest.setVerticalId(getVerticalId(transaction.getServiceType()));
            
            String deliveryMethodId = transaction.getDeliveryMethodId();
            if (deliveryMethodId == null || deliveryMethodId.trim().isEmpty()) {
                deliveryMethodId = "direct_topup";
            }
            executeRequest.setDeliveryMethodId(deliveryMethodId);
            
            if ("sms".equals(deliveryMethodId)) {
                String deliverTo = transaction.getDeliverTo();
                if (deliverTo == null || deliverTo.trim().isEmpty()) {
                    deliverTo = normalizePhoneTo12Digits(transaction.getCustomerPhone());
                }
                executeRequest.setDeliverTo(deliverTo);
            }
            
            logger.info("Executing EFASHE /vend/execute - TrxId: {}, Service: {}, Amount: {}, Account: {}", 
                executeRequest.getTrxId(), transaction.getServiceType(), executeRequest.getAmount(), 
                executeRequest.getCustomerAccountNumber());
            
            EfasheExecuteResponse executeResponse = null;
            try {
                executeResponse = efasheApiService.executeTransaction(executeRequest);
                
                // Log full execute response
                if (executeResponse != null) {
                    try {
                        ObjectMapper objectMapper = new ObjectMapper();
                        String executeResponseJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(executeResponse);
                        logger.info("═══════════════════════════════════════════════════════════════");
                        logger.info("📋 EFASHE EXECUTE RESPONSE (Parsed) - pollUntilSuccess");
                        logger.info("═══════════════════════════════════════════════════════════════");
                        logger.info("{}", executeResponseJson);
                        logger.info("═══════════════════════════════════════════════════════════════");
                    } catch (Exception logException) {
                        logger.warn("Could not serialize execute response to JSON: {}", logException.getMessage());
                        logger.info("Execute Response - HttpStatusCode: {}, PollEndpoint: {}, RetryAfterSecs: {}, Message: {}", 
                            executeResponse.getHttpStatusCode(),
                            executeResponse.getPollEndpoint(),
                            executeResponse.getRetryAfterSecs(),
                            executeResponse.getMessage());
                    }
                }
            } catch (RuntimeException e) {
                // Check if error is about invalid/expired trxId - need to re-validate and get new trxId
                if (e.getMessage() != null && 
                    (e.getMessage().contains("Invalid or expired trxID") || 
                     e.getMessage().contains("404") ||
                     (e.getMessage().contains("trxId") && e.getMessage().contains("not found")) ||
                     (e.getMessage().contains("trxId") && e.getMessage().contains("expired")))) {
                    logger.warn("⚠️ EFASHE execute failed with invalid/expired trxId error - Re-validating to get new trxId. " +
                        "Transaction ID: {}, Old TrxId: {}", transaction.getTransactionId(), transaction.getTrxId());
                    
                    try {
                        // Re-validate to get a new trxId
                        EfasheValidateRequest validateRequest = new EfasheValidateRequest();
                        validateRequest.setVerticalId(getVerticalId(transaction.getServiceType()));
                        validateRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                        
                        logger.info("Re-validating EFASHE transaction due to invalid/expired trxId - Vertical: {}, Customer Account Number: {}", 
                            validateRequest.getVerticalId(), validateRequest.getCustomerAccountNumber());
                        
                        EfasheValidateResponse validateResponse = efasheApiService.validateAccount(validateRequest);
                        
                        if (validateResponse != null && validateResponse.getTrxId() != null && !validateResponse.getTrxId().trim().isEmpty()) {
                            // Get new trxId from validate response
                            String newTrxId = validateResponse.getTrxId();
                            
                            // Update transaction with new trxId
                            String oldTrxId = transaction.getTrxId();
                            transaction.setTrxId(newTrxId);
                            efasheTransactionRepository.save(transaction);
                            
                            logger.info("✅ Got new trxId from re-validation: {} (old: {}) - Updated transaction record. Building new execute request with new trxId", 
                                newTrxId, oldTrxId);
                            
                            // Build a fresh execute request with the new trxId and all existing fields
                            EfasheExecuteRequest retryExecuteRequest = new EfasheExecuteRequest();
                            retryExecuteRequest.setTrxId(newTrxId); // Use NEW trxId from validate
                            retryExecuteRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber()); // Use existing
                            retryExecuteRequest.setAmount(transaction.getAmount()); // Use existing
                            retryExecuteRequest.setVerticalId(getVerticalId(transaction.getServiceType())); // Use existing
                            retryExecuteRequest.setDeliveryMethodId(deliveryMethodId); // Use existing
                            
                            if ("sms".equals(deliveryMethodId)) {
                                String deliverTo = transaction.getDeliverTo();
                                if (deliverTo == null || deliverTo.trim().isEmpty()) {
                                    deliverTo = normalizePhoneTo12Digits(transaction.getCustomerPhone());
                                }
                                retryExecuteRequest.setDeliverTo(deliverTo); // Use existing
                            }
                            
                            logger.info("Retrying execute with NEW trxId: {}, CustomerAccountNumber: {}, Amount: {}, VerticalId: {}, DeliveryMethodId: {}", 
                                newTrxId, retryExecuteRequest.getCustomerAccountNumber(), retryExecuteRequest.getAmount(), 
                                retryExecuteRequest.getVerticalId(), retryExecuteRequest.getDeliveryMethodId());
                            
                            // Retry execute with new trxId and all existing fields
                            executeResponse = efasheApiService.executeTransaction(retryExecuteRequest);
                            
                            // Log full execute response after retry
                            if (executeResponse != null) {
                                try {
                                    ObjectMapper objectMapper = new ObjectMapper();
                                    String executeResponseJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(executeResponse);
                                    logger.info("═══════════════════════════════════════════════════════════════");
                                    logger.info("📋 EFASHE EXECUTE RESPONSE (Parsed) - Retry After Re-validation (Invalid/Expired TrxId)");
                                    logger.info("═══════════════════════════════════════════════════════════════");
                                    logger.info("{}", executeResponseJson);
                                    logger.info("═══════════════════════════════════════════════════════════════");
                                } catch (Exception logException) {
                                    logger.warn("Could not serialize execute response to JSON: {}", logException.getMessage());
                                }
                            }
                            
                            logger.info("✅ EFASHE /vend/execute completed after re-validation (invalid/expired trxId) - HTTP Status: {}, Message: {}", 
                                executeResponse != null ? executeResponse.getHttpStatusCode() : "null",
                                executeResponse != null ? executeResponse.getMessage() : "null");
                        } else {
                            logger.error("Re-validation failed - Cannot get new trxId");
                            throw new RuntimeException("Re-validation failed - Cannot get new trxId");
                        }
                    } catch (Exception revalidateException) {
                        logger.error("Error during re-validation for invalid/expired trxId: ", revalidateException);
                        throw new RuntimeException("Failed to re-validate and retry execute after invalid/expired trxId error: " + revalidateException.getMessage(), revalidateException);
                    }
                }
                // Check if error is about "3 minute window" - need to re-validate and get new trxId
                else if (e.getMessage() != null && e.getMessage().contains("You cannot perform the same transaction within a 3 minute window")) {
                    logger.warn("⚠️ EFASHE execute failed with 3-minute window error - Re-validating to get new trxId");
                    
                    try {
                        // Re-validate to get a new trxId
                        EfasheValidateRequest validateRequest = new EfasheValidateRequest();
                        validateRequest.setVerticalId(getVerticalId(transaction.getServiceType()));
                        validateRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                        
                        logger.info("Re-validating EFASHE transaction - Vertical: {}, Customer Account Number: {}", 
                            validateRequest.getVerticalId(), validateRequest.getCustomerAccountNumber());
                        
                        EfasheValidateResponse validateResponse = efasheApiService.validateAccount(validateRequest);
                        
                        if (validateResponse != null && validateResponse.getTrxId() != null && !validateResponse.getTrxId().trim().isEmpty()) {
                            // Get new trxId from validate response
                            String newTrxId = validateResponse.getTrxId();
                            
                            // Update transaction with new trxId
                            transaction.setTrxId(newTrxId);
                            efasheTransactionRepository.save(transaction);
                            
                            logger.info("✅ Got new trxId from re-validation: {} - Building new execute request with new trxId", newTrxId);
                            
                            // Build a fresh execute request with the new trxId and all existing fields
                            EfasheExecuteRequest retryExecuteRequest = new EfasheExecuteRequest();
                            retryExecuteRequest.setTrxId(newTrxId); // Use NEW trxId from validate
                            retryExecuteRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber()); // Use existing
                            retryExecuteRequest.setAmount(transaction.getAmount()); // Use existing
                            retryExecuteRequest.setVerticalId(getVerticalId(transaction.getServiceType())); // Use existing
                            
                            // Reuse existing deliveryMethodId (already set above)
                            retryExecuteRequest.setDeliveryMethodId(deliveryMethodId); // Use existing
                            
                            if ("sms".equals(deliveryMethodId)) {
                                String deliverTo = transaction.getDeliverTo();
                                if (deliverTo == null || deliverTo.trim().isEmpty()) {
                                    deliverTo = normalizePhoneTo12Digits(transaction.getCustomerPhone());
                                }
                                retryExecuteRequest.setDeliverTo(deliverTo); // Use existing
                            }
                            
                            logger.info("Retrying execute with NEW trxId: {}, CustomerAccountNumber: {}, Amount: {}, VerticalId: {}, DeliveryMethodId: {}", 
                                newTrxId, retryExecuteRequest.getCustomerAccountNumber(), retryExecuteRequest.getAmount(), 
                                retryExecuteRequest.getVerticalId(), retryExecuteRequest.getDeliveryMethodId());
                            
                            // Retry execute with new trxId and all existing fields
                            try {
                                executeResponse = efasheApiService.executeTransaction(retryExecuteRequest);
                                
                                // Log full execute response after retry
                                if (executeResponse != null) {
                                    try {
                                        ObjectMapper objectMapper = new ObjectMapper();
                                        String executeResponseJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(executeResponse);
                                        logger.info("═══════════════════════════════════════════════════════════════");
                                        logger.info("📋 EFASHE EXECUTE RESPONSE (Parsed) - Retry After Re-validation");
                                        logger.info("═══════════════════════════════════════════════════════════════");
                                        logger.info("{}", executeResponseJson);
                                        logger.info("═══════════════════════════════════════════════════════════════");
                                    } catch (Exception logException) {
                                        logger.warn("Could not serialize execute response to JSON: {}", logException.getMessage());
                                    }
                                }
                                
                                logger.info("✅ EFASHE /vend/execute completed after re-validation - HTTP Status: {}, Message: {}", 
                                    executeResponse != null ? executeResponse.getHttpStatusCode() : "null",
                                    executeResponse != null ? executeResponse.getMessage() : "null");
                            } catch (RuntimeException retryException) {
                                // If retry still fails with 3-minute window error, try with fresh token
                                if (retryException.getMessage() != null && 
                                    (retryException.getMessage().contains("You cannot perform the same transaction within a 3 minute window") ||
                                     retryException.getMessage().contains("3 minute window"))) {
                                    logger.warn("⚠️ Execute still failed with 3-minute window error after re-validation. " +
                                        "Clearing token cache and retrying with fresh token. " +
                                        "New trxId: {}, CustomerAccountNumber: {}, Amount: {}.", 
                                        newTrxId, retryExecuteRequest.getCustomerAccountNumber(), retryExecuteRequest.getAmount());
                                    
                                    try {
                                        // Clear token cache to get fresh token
                                        efasheApiService.clearTokenCache();
                                        
                                        // Re-validate again with fresh token to get a new trxId
                                        logger.info("Re-validating again with fresh token - Vertical: {}, Customer Account Number: {}", 
                                            validateRequest.getVerticalId(), validateRequest.getCustomerAccountNumber());
                                        
                                        EfasheValidateResponse freshValidateResponse = efasheApiService.validateAccount(validateRequest);
                                        
                                        if (freshValidateResponse != null && freshValidateResponse.getTrxId() != null && !freshValidateResponse.getTrxId().trim().isEmpty()) {
                                            // Get fresh trxId from validate response
                                            String freshTrxId = freshValidateResponse.getTrxId();
                                            
                                            // Update transaction with fresh trxId
                                            transaction.setTrxId(freshTrxId);
                                            efasheTransactionRepository.save(transaction);
                                            
                                            logger.info("✅ Got fresh trxId from re-validation with fresh token: {} - Building new execute request", freshTrxId);
                                            
                                            // Build a fresh execute request with the fresh trxId
                                            EfasheExecuteRequest freshExecuteRequest = new EfasheExecuteRequest();
                                            freshExecuteRequest.setTrxId(freshTrxId);
                                            freshExecuteRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                                            freshExecuteRequest.setAmount(transaction.getAmount());
                                            freshExecuteRequest.setVerticalId(getVerticalId(transaction.getServiceType()));
                                            freshExecuteRequest.setDeliveryMethodId(deliveryMethodId);
                                            
                                            if ("sms".equals(deliveryMethodId)) {
                                                String deliverTo = transaction.getDeliverTo();
                                                if (deliverTo == null || deliverTo.trim().isEmpty()) {
                                                    deliverTo = normalizePhoneTo12Digits(transaction.getCustomerPhone());
                                                }
                                                freshExecuteRequest.setDeliverTo(deliverTo);
                                            }
                                            
                                            logger.info("Retrying execute with FRESH token and NEW trxId: {}, CustomerAccountNumber: {}, Amount: {}, VerticalId: {}, DeliveryMethodId: {}", 
                                                freshTrxId, freshExecuteRequest.getCustomerAccountNumber(), freshExecuteRequest.getAmount(), 
                                                freshExecuteRequest.getVerticalId(), freshExecuteRequest.getDeliveryMethodId());
                                            
                                            // Retry execute with fresh token and fresh trxId
                                            executeResponse = efasheApiService.executeTransaction(freshExecuteRequest);
                                            
                                            // Log full execute response after retry with fresh token
                                            if (executeResponse != null) {
                                                try {
                                                    ObjectMapper objectMapper = new ObjectMapper();
                                                    String executeResponseJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(executeResponse);
                                                    logger.info("═══════════════════════════════════════════════════════════════");
                                                    logger.info("📋 EFASHE EXECUTE RESPONSE (Parsed) - Retry After Fresh Token");
                                                    logger.info("═══════════════════════════════════════════════════════════════");
                                                    logger.info("{}", executeResponseJson);
                                                    logger.info("═══════════════════════════════════════════════════════════════");
                                                } catch (Exception logException) {
                                                    logger.warn("Could not serialize execute response to JSON: {}", logException.getMessage());
                                                }
                                            }
                                            
                                            logger.info("✅ EFASHE /vend/execute completed after fresh token retry - HTTP Status: {}, Message: {}", 
                                                executeResponse != null ? executeResponse.getHttpStatusCode() : "null",
                                                executeResponse != null ? executeResponse.getMessage() : "null");
                                        } else {
                                            logger.error("Re-validation with fresh token failed - Cannot get new trxId");
                                            throw new RuntimeException("Re-validation with fresh token failed - Cannot get new trxId");
                                        }
                                    } catch (Exception freshTokenException) {
                                        logger.error("Error during re-validation/execute with fresh token: ", freshTokenException);
                                        logger.warn("⚠️ Fresh token retry failed. This is expected if the service was already delivered. " +
                                            "Poll returned SUCCESS, so transaction is likely complete. Returning null.");
                                        // Don't throw - poll said SUCCESS, so transaction is likely complete
                                        executeResponse = null;
                                    }
                                } else {
                                    // Different error, re-throw
                                    throw retryException;
                                }
                            }
                        } else {
                            logger.error("Re-validation failed - Cannot get new trxId");
                            throw new RuntimeException("Re-validation failed - Cannot get new trxId");
                        }
                    } catch (Exception revalidateException) {
                        // If re-validation itself fails, log and continue since poll already returned SUCCESS
                        logger.error("Error during re-validation: ", revalidateException);
                        logger.warn("⚠️ Re-validation failed, but poll returned SUCCESS - transaction is likely already complete. Continuing...");
                        // Don't throw - poll said SUCCESS, so transaction is complete
                        executeResponse = null;
                    }
                } else {
                    // Not a 3-minute window error, re-throw
                    throw e;
                }
            }
            
            return executeResponse;
        } catch (Exception e) {
            logger.error("═══════════════════════════════════════════════════════════════");
            logger.error("❌ Error in executeWithRetry - Transaction ID: {}", transaction.getTransactionId());
            logger.error("═══════════════════════════════════════════════════════════════");
            logger.error("Error Type: {}", e.getClass().getName());
            logger.error("Error Message: {}", e.getMessage());
            if (e.getCause() != null) {
                logger.error("Caused by: {} - {}", e.getCause().getClass().getName(), e.getCause().getMessage());
            }
            logger.error("Stack Trace:", e);
            logger.error("═══════════════════════════════════════════════════════════════");
            
            return null;
        }
    }
    
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
                        
                        // Poll returned SUCCESS - this means EFASHE already executed the transaction successfully
                        // Use the SUCCESS from poll directly, don't try to execute again
                        logger.info("Poll returned SUCCESS - Using SUCCESS from EFASHE poll response. Service was already delivered ({})", 
                            transaction.getServiceType());
                        
                        // Use the SUCCESS from poll - mark transaction as SUCCESS
                        transaction.setEfasheStatus("SUCCESS");
                        transaction.setMessage("EFASHE transaction completed successfully - Service delivered (confirmed by poll)");
                        efasheTransactionRepository.save(transaction);
                        logger.info("✅ Transaction marked as SUCCESS - Using SUCCESS from EFASHE poll response");
                        
                        // For ELECTRICITY service, extract and store token and KWH information
                        // Only process if execute was successful
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
                            
                            // Call /electricity/tokens endpoint to get token and save it (get latest by timestamp)
                            try {
                                String meterNumber = transaction.getCustomerAccountNumber();
                                if (meterNumber != null && !meterNumber.trim().isEmpty()) {
                                    logger.info("ELECTRICITY service - Calling /electricity/tokens endpoint for meter: {} (requesting 3 tokens to find latest)", meterNumber);
                                    // Request last 3 tokens to find the latest one by timestamp
                                    ElectricityTokensResponse tokensResponse = efasheApiService.getElectricityTokens(meterNumber, 3);
                                    
                                    if (tokensResponse != null && tokensResponse.getData() != null && !tokensResponse.getData().isEmpty()) {
                                        // Find the latest token by timestamp
                                        ElectricityTokensResponse.ElectricityTokenData latestTokenData = getLatestTokenByTimestamp(tokensResponse.getData());
                                        
                                        if (latestTokenData != null && latestTokenData.getToken() != null && !latestTokenData.getToken().trim().isEmpty()) {
                                            String latestToken = latestTokenData.getToken();
                                            // Save the latest token to the database
                                            transaction.setToken(latestToken);
                                            logger.info("ELECTRICITY service - Saved latest token from /electricity/tokens: {} (timestamp: {})", latestToken, latestTokenData.getTstamp());
                                            
                                            // Always update/replace token in message with latest token
                                            String currentMessage = messageBuilder.toString();
                                            // Remove old token if exists
                                            currentMessage = currentMessage.replaceAll("(?i)Token:\\s*[0-9\\-]+", "");
                                            // Remove any double separators
                                            currentMessage = currentMessage.replaceAll("\\s*\\|\\s*\\|\\s*", " | ");
                                            currentMessage = currentMessage.replaceAll("^\\s*\\|\\s*", "").replaceAll("\\s*\\|\\s*$", "");
                                            messageBuilder = new StringBuilder(currentMessage.trim());
                                            
                                            // Add latest token
                                            if (messageBuilder.length() > 0) {
                                                messageBuilder.append(" | ");
                                            }
                                            messageBuilder.append("Token: ").append(latestToken);
                                            
                                            // Also update/replace KWH if available from latest token response
                                            if (latestTokenData.getUnits() != null) {
                                                String unitsStr = String.format("%.1f", latestTokenData.getUnits());
                                                // Remove old KWH if exists
                                                String messageStr = messageBuilder.toString();
                                                messageStr = messageStr.replaceAll("(?i)KWH:\\s*[0-9.]+", "");
                                                messageStr = messageStr.replaceAll("\\s*\\|\\s*\\|\\s*", " | ");
                                                messageStr = messageStr.replaceAll("^\\s*\\|\\s*", "").replaceAll("\\s*\\|\\s*$", "");
                                                messageBuilder = new StringBuilder(messageStr.trim());
                                                
                                                // Add latest KWH
                                                if (messageBuilder.length() > 0) {
                                                    messageBuilder.append(" | ");
                                                }
                                                messageBuilder.append("KWH: ").append(unitsStr);
                                                logger.info("ELECTRICITY service - Updated message with latest KWH from /electricity/tokens: {}", unitsStr);
                                            }
                                            
                                            transaction.setMessage(messageBuilder.toString());
                                            logger.info("ELECTRICITY service - Updated message with latest token and KWH from /electricity/tokens endpoint");
                                        } else {
                                            logger.warn("ELECTRICITY service - Latest token from /electricity/tokens is null or empty");
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
                        
                        // For ELECTRICITY service, validate that tokens were received
                        if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                            // Check if token is empty - if so, mark as FAILED
                            if (transaction.getToken() == null || transaction.getToken().trim().isEmpty()) {
                                logger.error("❌ ELECTRICITY transaction - No token received. Poll returned SUCCESS but electricityTokens.data is empty. Marking as FAILED.");
                                transaction.setEfasheStatus("FAILED");
                                transaction.setErrorMessage("Poll returned SUCCESS but no electricity token was received. Service may not be delivered.");
                                efasheTransactionRepository.save(transaction);
                                logger.error("❌ ELECTRICITY transaction FAILED - No token. NOT sending notifications. Throwing error.");
                                throw new RuntimeException("ELECTRICITY transaction: Poll returned SUCCESS but no token was received. Transaction ID: " + transaction.getTransactionId());
                            }
                        }
                        
                        // Update transaction
                        efasheTransactionRepository.save(transaction);
                        logger.info("Updated transaction - Transaction ID: {}, EFASHE Status: SUCCESS (from poll attempt {})", 
                            transaction.getTransactionId(), retryCount);
                        
                        // Poll returned SUCCESS - send notifications
                        sendWhatsAppNotification(transaction);
                        logger.info("=== END Automatic Polling (SUCCESS) ===");
                        return true;
                        
                    } else if (trimmedStatus != null && "FAILED".equalsIgnoreCase(trimmedStatus)) {
                        // Transaction failed - stop retrying
                        logger.error("❌ EFASHE transaction FAILED on attempt {}/{} - Status: {} - NOT sending notifications. Throwing error.", retryCount, maxRetries, trimmedStatus);
                        transaction.setEfasheStatus("FAILED");
                        transaction.setMessage(pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction failed");
                        transaction.setErrorMessage("EFASHE transaction failed: " + pollResponse.getMessage());
                        efasheTransactionRepository.save(transaction);
                        logger.info("=== END Automatic Polling (FAILED) ===");
                        throw new RuntimeException("EFASHE transaction FAILED: " + (pollResponse.getMessage() != null ? pollResponse.getMessage() : "Unknown error") + ". Transaction ID: " + transaction.getTransactionId());
                        
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
                String errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
                
                // Check if this is a 404 error (poll endpoint not found/expired)
                if (errorMessage != null && (errorMessage.contains("EFASHE_POLL_ENDPOINT_NOT_FOUND") || 
                    (errorMessage.contains("404") && errorMessage.contains("page not found")))) {
                    logger.error("❌ EFASHE poll endpoint returned 404 (endpoint expired or invalid) - Stopping retries - Transaction ID: {}", 
                        transaction.getTransactionId());
                    
                    // Mark transaction as failed due to endpoint expiration
                    transaction.setEfasheStatus("FAILED");
                    transaction.setErrorMessage("EFASHE poll endpoint expired or invalid (404). Cannot determine final transaction status.");
                    transaction.setMessage("EFASHE transaction status could not be determined - poll endpoint expired");
                    efasheTransactionRepository.save(transaction);
                    
                    logger.info("=== END Automatic Polling (ENDPOINT EXPIRED - 404) ===");
                    return false; // Stop retrying - this is a permanent error
                }
                
                logger.error("Error polling EFASHE status on attempt {}/{}: {}", retryCount, maxRetries, e.getMessage(), e);
                
                // Wait before retry on error (for non-404 errors)
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
            
            // CRITICAL: Do not send notifications for FAILED transactions
            if ("FAILED".equalsIgnoreCase(transaction.getEfasheStatus())) {
                logger.warn("⚠️ Transaction is marked as FAILED - NOT sending WhatsApp/SMS notifications. Transaction ID: {}, Error: {}", 
                    transaction.getTransactionId(), transaction.getErrorMessage());
                return;
            }
            
            // For ELECTRICITY transactions, ensure tokens are available before sending notifications
            if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                // Check if transaction has error message about no tokens
                if (transaction.getErrorMessage() != null && 
                    transaction.getErrorMessage().contains("no electricity tokens were delivered")) {
                    logger.warn("⚠️ ELECTRICITY transaction has no tokens - NOT sending WhatsApp/SMS notifications. Transaction ID: {}", 
                        transaction.getTransactionId());
                    return;
                }
            }
            
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
            String search,
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
        
        // Process search term - try to normalize if it looks like a phone number
        String normalizedSearchPhone = null;
        if (search != null && !search.trim().isEmpty()) {
            String searchTerm = search.trim();
            // Try to normalize if it looks like a phone number (contains only digits, +, spaces, or -)
            if (searchTerm.matches("^[\\d\\+\\s\\-]+$")) {
                try {
                    normalizedSearchPhone = normalizePhoneTo12Digits(searchTerm);
                    logger.debug("Search term looks like phone number, normalized: {} -> {}", searchTerm, normalizedSearchPhone);
                } catch (Exception e) {
                    // Not a valid phone number, use as-is
                    logger.debug("Search term does not normalize to valid phone, using as-is: {}", searchTerm);
                }
            }
        }
        
        String roleInfo = isAdmin ? "ADMIN" : (isReceiver ? "RECEIVER" : (isUser ? "USER" : "UNKNOWN"));
        logger.info("Fetching EFASHE transactions - Role: {}, ServiceType: {}, Phone: {} (normalized: {}), Search: {} (normalized phone: {}), Page: {}, Size: {}, FromDate: {}, ToDate: {}", 
            roleInfo, serviceType, phone, normalizedPhone, search, normalizedSearchPhone, page, size, fromDate, toDate);
        
        // Build dynamic query to avoid PostgreSQL type inference issues with nullable parameters
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT t FROM EfasheTransaction t WHERE 1=1 ");
        
        // Add service type filter if provided
        if (serviceType != null) {
            queryBuilder.append("AND t.serviceType = :serviceType ");
        }
        
        // Add phone filter if provided (only if search is not provided, to avoid conflicts)
        if (normalizedPhone != null && !normalizedPhone.trim().isEmpty() && (search == null || search.trim().isEmpty())) {
            queryBuilder.append("AND t.customerPhone = :customerPhone ");
        }
        
        // Add search filter if provided (searches in phone, name, and transaction ID)
        if (search != null && !search.trim().isEmpty()) {
            String searchTerm = search.trim();
            queryBuilder.append("AND (LOWER(t.customerPhone) LIKE :searchTerm ");
            if (normalizedSearchPhone != null) {
                queryBuilder.append("OR t.customerPhone = :normalizedSearchPhone ");
            }
            queryBuilder.append("OR LOWER(t.customerAccountName) LIKE :searchTerm ");
            queryBuilder.append("OR LOWER(t.transactionId) LIKE :searchTerm) ");
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
            || (isAdmin || isReceiver) && normalizedPhone != null && !normalizedPhone.trim().isEmpty()
            || (search != null && !search.trim().isEmpty());
        
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
        if (normalizedPhone != null && !normalizedPhone.trim().isEmpty() && (search == null || search.trim().isEmpty())) {
            query.setParameter("customerPhone", normalizedPhone);
        }
        if (search != null && !search.trim().isEmpty()) {
            String searchTerm = search.trim();
            query.setParameter("searchTerm", "%" + searchTerm.toLowerCase() + "%");
            
            // If search term was normalized to a phone number, also search with normalized version
            if (normalizedSearchPhone != null) {
                query.setParameter("normalizedSearchPhone", normalizedSearchPhone);
            }
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
        if (normalizedPhone != null && !normalizedPhone.trim().isEmpty() && (search == null || search.trim().isEmpty())) {
            countQueryBuilder.append("AND t.customerPhone = :customerPhone ");
        }
        if (search != null && !search.trim().isEmpty()) {
            countQueryBuilder.append("AND (LOWER(t.customerPhone) LIKE :searchTerm ");
            if (normalizedSearchPhone != null) {
                countQueryBuilder.append("OR t.customerPhone = :normalizedSearchPhone ");
            }
            countQueryBuilder.append("OR LOWER(t.customerAccountName) LIKE :searchTerm ");
            countQueryBuilder.append("OR LOWER(t.transactionId) LIKE :searchTerm) ");
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
        if (normalizedPhone != null && !normalizedPhone.trim().isEmpty() && (search == null || search.trim().isEmpty())) {
            countQuery.setParameter("customerPhone", normalizedPhone);
        }
        if (search != null && !search.trim().isEmpty()) {
            String searchTerm = search.trim();
            countQuery.setParameter("searchTerm", "%" + searchTerm.toLowerCase() + "%");
            
            // If search term is a phone number, also search with normalized version
            if (normalizedSearchPhone != null) {
                countQuery.setParameter("normalizedSearchPhone", normalizedSearchPhone);
            }
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
     * Get electricity tokens for a meter number (test/debug endpoint)
     * @param meterNumber Meter number to check tokens for
     * @param numTokens Number of tokens to retrieve (default: 1)
     * @return ElectricityTokensResponse with token data
     */
    public ElectricityTokensResponse getElectricityTokens(String meterNumber, Integer numTokens) {
        logger.info("Getting electricity tokens for meter: {}, numTokens: {}", meterNumber, numTokens);
        return efasheApiService.getElectricityTokens(meterNumber, numTokens);
    }
    
    /**
     * Get the latest token from a list of tokens by comparing timestamps
     * @param tokens List of token data
     * @return The token with the latest timestamp, or first token if timestamps can't be compared
     */
    private ElectricityTokensResponse.ElectricityTokenData getLatestTokenByTimestamp(
            List<ElectricityTokensResponse.ElectricityTokenData> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        
        if (tokens.size() == 1) {
            return tokens.get(0);
        }
        
        // Find token with latest timestamp
        ElectricityTokensResponse.ElectricityTokenData latest = tokens.get(0);
        String latestTimestamp = latest.getTstamp();
        
        for (ElectricityTokensResponse.ElectricityTokenData token : tokens) {
            String tokenTimestamp = token.getTstamp();
            if (tokenTimestamp != null && !tokenTimestamp.trim().isEmpty()) {
                if (latestTimestamp == null || latestTimestamp.trim().isEmpty()) {
                    latest = token;
                    latestTimestamp = tokenTimestamp;
                } else {
                    // Compare timestamps (ISO 8601 format: "2026-01-18T10:30:00" or similar)
                    try {
                        // Try to parse and compare timestamps
                        java.time.LocalDateTime latestTime = parseTimestamp(latestTimestamp);
                        java.time.LocalDateTime tokenTime = parseTimestamp(tokenTimestamp);
                        
                        if (tokenTime != null && latestTime != null && tokenTime.isAfter(latestTime)) {
                            latest = token;
                            latestTimestamp = tokenTimestamp;
                            logger.debug("Found newer token - Timestamp: {}, Previous: {}", tokenTimestamp, latestTimestamp);
                        }
                    } catch (Exception e) {
                        logger.warn("Could not parse timestamp for comparison: {} vs {}, error: {}", 
                            tokenTimestamp, latestTimestamp, e.getMessage());
                        // If parsing fails, compare as strings (lexicographic comparison works for ISO 8601)
                        if (tokenTimestamp.compareTo(latestTimestamp) > 0) {
                            latest = token;
                            latestTimestamp = tokenTimestamp;
                        }
                    }
                }
            }
        }
        
        logger.info("Selected latest token - Timestamp: {}, Token: {}", latestTimestamp, latest.getToken());
        return latest;
    }
    
    /**
     * Parse timestamp string to LocalDateTime
     * Supports various ISO 8601 formats
     */
    private java.time.LocalDateTime parseTimestamp(String timestampStr) {
        if (timestampStr == null || timestampStr.trim().isEmpty()) {
            return null;
        }
        
        String trimmed = timestampStr.trim();
        // Try different date formats - support all common formats to ensure we can identify the latest token
        java.time.format.DateTimeFormatter[] formatters = {
            // EFASHE formats: "2/1/2026, 12:51:20 PM" or "12/31/2026, 12:51:20 PM" (M/d/yyyy supports 1-2 digits)
            java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy, hh:mm:ss a", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy, h:mm:ss a", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy, hh:mm:ss a", java.util.Locale.ENGLISH),
            // European format: "dd/MM/yyyy, h:mm:ss a"
            java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy, h:mm:ss a", java.util.Locale.ENGLISH),
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy, h:mm:ss a", java.util.Locale.ENGLISH),
            // ISO formats
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            // Other common formats
            java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
            java.time.format.DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS")
        };
        
        for (java.time.format.DateTimeFormatter formatter : formatters) {
            try {
                return java.time.LocalDateTime.parse(trimmed, formatter);
            } catch (Exception e) {
                // Try next format
            }
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
    
    /**
     * Process refund for an EFASHE transaction
     * POST /api/efashe/refund/{transactionId}
     * 
     * Refunds the amount paid minus cashback (customer cashback + besoft share)
     * Transfers from adminPhone (DEBIT) to receiverPhone (CREDIT)
     * 
     * IMPORTANT: This method ONLY uses MoPay API - NO EFASHE API calls are made during refund processing.
     * Refund is a money transfer operation, not a service delivery operation.
     * 
     * @param transactionId The EFASHE transaction ID
     * @param request Refund request with adminPhone, receiverPhone, and optional message
     * @return Success message with refund details
     */
    @Transactional
    public String processRefund(String transactionId, EfasheRefundRequest request) {
        logger.info("=== PROCESS REFUND ENDPOINT START ===");
        logger.info("Transaction ID: {}, Admin Phone: {}, Receiver Phone: {}", 
            transactionId, request.getAdminPhone(), request.getReceiverPhone());
        
        // Find transaction
        Optional<EfasheTransaction> transactionOpt = findTransactionById(transactionId);
        if (!transactionOpt.isPresent()) {
            throw new RuntimeException("EFASHE transaction not found with ID: " + transactionId);
        }
        
        EfasheTransaction transaction = transactionOpt.get();
        
        // Validate transaction has amount
        if (transaction.getAmount() == null || transaction.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Transaction amount is null or zero. Cannot process refund.");
        }
        
        // Normalize phone numbers
        String normalizedAdminPhone = normalizePhoneTo12Digits(request.getAdminPhone());
        String normalizedReceiverPhone = normalizePhoneTo12Digits(request.getReceiverPhone());
        
        // Calculate refund amount: transaction amount - (customer cashback + besoft share)
        BigDecimal transactionAmount = transaction.getAmount();
        BigDecimal customerCashback = transaction.getCustomerCashbackAmount() != null ? 
            transaction.getCustomerCashbackAmount() : BigDecimal.ZERO;
        BigDecimal besoftShare = transaction.getBesoftShareAmount() != null ? 
            transaction.getBesoftShareAmount() : BigDecimal.ZERO;
        
        BigDecimal totalCashback = customerCashback.add(besoftShare);
        BigDecimal refundAmount = transactionAmount.subtract(totalCashback);
        
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Refund amount is zero or negative. Transaction amount: " + 
                transactionAmount + ", Total cashback: " + totalCashback);
        }
        
        logger.info("Refund calculation - Transaction Amount: {}, Customer Cashback: {}, Besoft Share: {}, Total Cashback: {}, Refund Amount: {}", 
            transactionAmount, customerCashback, besoftShare, totalCashback, refundAmount);
        
        // Generate unique transaction ID for refund
        String timestamp = String.valueOf(System.currentTimeMillis());
        String refundTransactionId = transactionId + "-REFUND-" + timestamp;
        
        // Build refund message
        String refundMessage = request.getMessage() != null && !request.getMessage().trim().isEmpty() 
            ? request.getMessage() 
            : "EFASHE " + transaction.getServiceType() + " - Refund";
        
        // Initiate MoPay refund transfer
        // NOTE: We do NOT call EFASHE API during refund processing - only MoPay transfer
        try {
            MoPayInitiateRequest refundRequest = new MoPayInitiateRequest();
            refundRequest.setTransaction_id(refundTransactionId);
            refundRequest.setAmount(refundAmount);
            refundRequest.setCurrency("RWF");
            refundRequest.setPhone(normalizedAdminPhone); // From: admin phone (DEBIT)
            refundRequest.setPayment_mode("MOBILE");
            refundRequest.setMessage(refundMessage);
            
            // Create transfer to receiver phone
            List<MoPayInitiateRequest.Transfer> transfers = new ArrayList<>();
            MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
            transfer.setAmount(refundAmount);
            Long receiverPhoneLong = Long.parseLong(normalizedReceiverPhone);
            transfer.setPhone(receiverPhoneLong); // To: receiver phone (CREDIT)
            transfer.setMessage(refundMessage);
            transfers.add(transfer);
            refundRequest.setTransfers(transfers);
            
            logger.info("Initiating refund transfer - Transaction ID: {}, Amount: {}, From: {}, To: {}", 
                refundTransactionId, refundAmount, normalizedAdminPhone, normalizedReceiverPhone);
            
            // Call MoPay to initiate the refund transfer
            MoPayResponse refundResponse = moPayService.initiatePayment(refundRequest);
            
            // DETAILED LOGGING: Log the complete MoPay initiate response
            logger.info("═══════════════════════════════════════════════════════════════");
            logger.info("📋 COMPLETE MoPay Refund Initiate Response:");
            logger.info("   Request Transaction ID: {}", refundTransactionId);
            if (refundResponse != null) {
                logger.info("   Status Code: {}", refundResponse.getStatus());
                logger.info("   Success Flag: {}", refundResponse.getSuccess());
                logger.info("   Transaction ID (from response): {}", refundResponse.getTransactionId());
                logger.info("   Legacy Transaction ID: {}", refundResponse.getTransaction_id());
                logger.info("   Message: {}", refundResponse.getMessage());
                logger.info("   Error Message: {}", refundResponse.getErrorMessage());
                logger.info("   Full Response Object: {}", refundResponse);
            } else {
                logger.warn("   ⚠️ MoPay response is NULL");
            }
            logger.info("═══════════════════════════════════════════════════════════════");
            
            // Check if refund was successful: status 201 (CREATED) or 200 (OK) OR success flag is true
            boolean isRefundSuccess = false;
            if (refundResponse != null) {
                Integer statusCode = refundResponse.getStatus();
                isRefundSuccess = (statusCode != null && (statusCode == 200 || statusCode == 201))
                    || (refundResponse.getSuccess() != null && refundResponse.getSuccess())
                    || (refundResponse.getTransactionId() != null && !refundResponse.getTransactionId().isEmpty());
            }
            
            if (!isRefundSuccess) {
                throw new RuntimeException("Refund transfer failed. MoPay response: " + 
                    (refundResponse != null ? refundResponse.toString() : "null"));
            }
            
            logger.info("✅ Refund transfer initiated successfully - Transaction ID: {}, MoPay Status: {}, MoPay Transaction ID: {}", 
                refundTransactionId, refundResponse.getStatus(), refundResponse.getTransactionId());
            
            // Get the actual MoPay transaction ID for status checking
            // This is the ID that should be returned and used to track the refund status
            String actualRefundTransactionId = refundResponse.getTransactionId() != null ? 
                refundResponse.getTransactionId() : refundTransactionId;
            
            logger.info("📌 MoPay Transaction ID: {}", actualRefundTransactionId);
            
            // Poll refund status synchronously - DO NOT return success until we find status: 200
            // Poll every 2 seconds for up to 1 minute (60 seconds)
            logger.info("⏳ Polling refund status - Transaction ID: {}. Will check every 2 seconds for up to 60 seconds (1 minute).", 
                actualRefundTransactionId);
            logger.info("⚠️ Will NOT return success until we find status: 200 in MoPay response");
            
            // Poll refund status synchronously - wait for status: 200
            String finalStatus = pollRefundStatusSync(actualRefundTransactionId);
            
            logger.info("📊 Refund status check result - Transaction ID: {}, Final Status: {}", actualRefundTransactionId, finalStatus);
            
            // Mark refund as processed in error message
            String existingErrorMessage = transaction.getErrorMessage() != null ? transaction.getErrorMessage() : "";
            transaction.setErrorMessage(existingErrorMessage + " | REFUND_PROCESSED");
            efasheTransactionRepository.save(transaction);
            
            // Only save refund history if status is SUCCESS or FAILED (not PENDING)
            if ("SUCCESS".equals(finalStatus) || "FAILED".equals(finalStatus)) {
                EfasheRefundHistory refundHistory = new EfasheRefundHistory();
                refundHistory.setEfasheTransaction(transaction);
                refundHistory.setOriginalTransactionId(transactionId);
                refundHistory.setRefundTransactionId(actualRefundTransactionId);
                refundHistory.setRefundAmount(refundAmount);
                refundHistory.setAdminPhone(normalizedAdminPhone);
                refundHistory.setReceiverPhone(normalizedReceiverPhone);
                refundHistory.setMessage(refundMessage);
                refundHistory.setStatus(finalStatus);
                refundHistory.setMopayStatus(finalStatus.equals("SUCCESS") ? "200" : "FAILED");
                refundHistory = efasheRefundHistoryRepository.save(refundHistory);
                
                logger.info("✅ Refund history saved - History ID: {}, Refund Transaction ID: {}, Status: {}", 
                    refundHistory.getId(), refundHistory.getRefundTransactionId(), finalStatus);
                
                // ONLY IF STATUS IS SUCCESS (status 200), THEN update EFASHE transaction to REFUNDED and send notifications
                if ("SUCCESS".equals(finalStatus)) {
                    logger.info("✅ Refund status is SUCCESS (status 200) - Updating EFASHE transaction to REFUNDED");
                    
                    // Update EFASHE transaction status to REFUNDED
                    transaction.setEfasheStatus("REFUNDED");
                    efasheTransactionRepository.save(transaction);
                    
                    logger.info("✅ EFASHE transaction {} updated to REFUNDED", transactionId);
                    
                    // Send WhatsApp and SMS notifications
                    logger.info("✅ Now sending WhatsApp and SMS notifications to: {}", normalizedReceiverPhone);
                    try {
                        String serviceName = transaction.getServiceType() != null ? transaction.getServiceType().toString() : "payment";
                        String refundAmountStr = refundAmount.toPlainString();
                        String transactionAmountStr = transactionAmount.toPlainString();
                        String totalCashbackStr = totalCashback.toPlainString();
                        
                        // SMS message - show refund amount and cashback deduction
                        String smsMessage = String.format(
                            "Bepay-Efashe-%s Refund: You paid %s RWF. Refunded: %s RWF. Cashback deducted: %s RWF. Transaction ID: %s. Thanks for using Bepay POCHI App",
                            serviceName.toUpperCase(),
                            transactionAmountStr,
                            refundAmountStr,
                            totalCashbackStr,
                            transactionId
                        );
                        
                        // WhatsApp message - show refund amount and cashback deduction
                        String whatsAppMessage = String.format(
                            "Bepay-Efashe-%s Refund: You paid %s RWF. Refunded: %s RWF. Cashback deducted: %s RWF. Transaction ID: %s. Thanks for using Bepay POCHI App",
                            serviceName.toUpperCase(),
                            transactionAmountStr,
                            refundAmountStr,
                            totalCashbackStr,
                            transactionId
                        );
                        
                        messagingService.sendSms(smsMessage, normalizedReceiverPhone);
                        whatsAppService.sendWhatsApp(whatsAppMessage, normalizedReceiverPhone);
                        
                        logger.info("✅ Refund notifications sent - SMS and WhatsApp sent to: {}", normalizedReceiverPhone);
                    } catch (Exception notificationException) {
                        logger.error("Error sending refund notifications: ", notificationException);
                        // Don't fail refund if notifications fail
                    }
                } else {
                    logger.info("⚠️ Refund status is NOT SUCCESS (Status: {}) - Marking as FAILED, NOT sending WhatsApp/SMS notifications", finalStatus);
                    // Mark as FAILED - don't send notifications
                    transaction.setEfasheStatus("FAILED");
                    efasheTransactionRepository.save(transaction);
                }
            } else {
                logger.warn("⚠️ Refund status is still PENDING after polling - not saving to database. Transaction ID: {}", 
                    actualRefundTransactionId);
            }
            
            logger.info("✅ Refund processed - Amount: {}, From: {}, To: {}, Status: {}", 
                refundAmount, normalizedAdminPhone, normalizedReceiverPhone, finalStatus);
            logger.info("=== PROCESS REFUND ENDPOINT END ===");
            
            // Only return success if we found status: 200, otherwise throw error
            if ("SUCCESS".equals(finalStatus)) {
                // Return the MoPay transaction ID
                return actualRefundTransactionId;
            } else {
                throw new RuntimeException("Refund status check failed. Status: " + finalStatus + ". Transaction ID: " + actualRefundTransactionId);
            }
            
        } catch (Exception e) {
            logger.error("Error processing refund: ", e);
            throw new RuntimeException("Failed to process refund: " + e.getMessage(), e);
        }
    }
    
    /**
     * Poll refund status synchronously (wait for result)
     * Polls every 2 seconds, maximum 30 times (60 seconds / 1 minute total)
     * Returns SUCCESS, FAILED, or PENDING (will be converted to FAILED if still PENDING after max attempts)
     * 
     * IMPORTANT: This method ONLY uses MoPay API - NO EFASHE API calls are made.
     * Refund status checking is done via MoPay's check-status endpoint only.
     * 
     * @param refundTransactionId MoPay refund transaction ID
     * @return Status: SUCCESS, FAILED, or PENDING (converted to FAILED if still PENDING after max attempts)
     */
    private String pollRefundStatusSync(String refundTransactionId) {
        logger.info("=== SYNCHRONOUS REFUND POLLING START ===");
        logger.info("Refund Transaction ID: {}, Will poll every 5 seconds for up to 60 seconds (1 minute)", refundTransactionId);
        logger.info("NOTE: Only using MoPay API for status checking - NO EFASHE API calls");
        
        int maxAttempts = 12; // 60 seconds / 1 minute total (5 seconds per attempt)
        int pollIntervalSeconds = 5;
        String finalStatus = "PENDING";
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                logger.info("Refund status poll attempt {}/{} - Transaction ID: {}", attempt, maxAttempts, refundTransactionId);
                
                // Check refund status using MoPay check-status endpoint ONLY (no EFASHE calls)
                MoPayResponse statusResponse = moPayService.checkTransactionStatus(refundTransactionId);
                
                // DETAILED LOGGING: Log the complete MoPay response
                logger.info("═══════════════════════════════════════════════════════════════");
                logger.info("📋 COMPLETE MoPay Refund Status Response:");
                logger.info("   Transaction ID: {}", refundTransactionId);
                if (statusResponse != null) {
                    logger.info("   Status Code: {}", statusResponse.getStatus());
                    logger.info("   Success Flag: {}", statusResponse.getSuccess());
                    logger.info("   Transaction ID (from response): {}", statusResponse.getTransactionId());
                    logger.info("   Legacy Transaction ID: {}", statusResponse.getTransaction_id());
                    logger.info("   Message: {}", statusResponse.getMessage());
                    logger.info("   Error Message: {}", statusResponse.getErrorMessage());
                    logger.info("   Full Response Object: {}", statusResponse);
                } else {
                    logger.warn("   ⚠️ MoPay response is NULL");
                }
                logger.info("═══════════════════════════════════════════════════════════════");
                
                if (statusResponse != null) {
                    Integer statusCode = statusResponse.getStatus();
                    Boolean successFlag = statusResponse.getSuccess();
                    
                    // Check specifically for status: 200 in the MoPay response
                    // The response should have: "status": 200
                    boolean isSuccess = (statusCode != null && statusCode == 200);
                    
                    logger.info("Refund status check - Transaction ID: {}, Status Code: {}, Success Flag: {}, Is Success (status==200): {}", 
                        refundTransactionId, statusCode, successFlag, isSuccess);
                    
                    if (isSuccess) {
                        finalStatus = "SUCCESS";
                        logger.info("✅ Refund status is SUCCESS (status: 200 found) - Transaction ID: {}", refundTransactionId);
                        logger.info("=== SYNCHRONOUS REFUND POLLING END (SUCCESS) ===");
                        return "SUCCESS";
                    } else {
                        // Check if it's explicitly failed
                        if (statusCode != null && statusCode >= 400) {
                            finalStatus = "FAILED";
                            logger.warn("⚠️ Refund status is FAILED - Transaction ID: {}, Status Code: {}", 
                                refundTransactionId, statusCode);
                            logger.info("=== SYNCHRONOUS REFUND POLLING END (FAILED) ===");
                            return "FAILED";
                        }
                        // Check if success flag is explicitly false (transaction failed)
                        if (statusResponse.getSuccess() != null && !statusResponse.getSuccess() && statusCode != null && statusCode < 400) {
                            finalStatus = "FAILED";
                            logger.warn("⚠️ Refund status is FAILED (success flag is false) - Transaction ID: {}", 
                                refundTransactionId);
                            logger.info("=== SYNCHRONOUS REFUND POLLING END (FAILED) ===");
                            return "FAILED";
                        }
                        // Still pending, continue polling
                        logger.info("Refund status is still PENDING - will retry in {} second(s)", pollIntervalSeconds);
                    }
                } else {
                    logger.warn("MoPay status response is null for refund transaction: {}", refundTransactionId);
                }
                
                // Wait before next attempt (except on last attempt)
                if (attempt < maxAttempts) {
                    Thread.sleep(pollIntervalSeconds * 1000);
                }
                
            } catch (InterruptedException e) {
                logger.error("Refund polling thread interrupted: ", e);
                Thread.currentThread().interrupt();
                logger.info("=== SYNCHRONOUS REFUND POLLING END (INTERRUPTED) ===");
                return "FAILED";
            } catch (Exception e) {
                logger.error("Error checking refund status (attempt {}/{}): ", attempt, maxAttempts, e);
                // Continue to next attempt even on error
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(pollIntervalSeconds * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.info("=== SYNCHRONOUS REFUND POLLING END (INTERRUPTED) ===");
                        return "FAILED";
                    }
                }
            }
        }
        
        // If still PENDING after all attempts, mark as FAILED
        logger.warn("⚠️ Refund status check completed but status is still PENDING after {} attempts - Marking as FAILED. Transaction ID: {}", 
            maxAttempts, refundTransactionId);
        logger.info("=== SYNCHRONOUS REFUND POLLING END (PENDING -> FAILED) ===");
        return "FAILED";
    }
    
    /**
     * Poll refund status in the background (legacy method - kept for backward compatibility)
     * Polls every 5 seconds, maximum 3 times
     * Updates refund history status when SUCCESS is detected
     * 
     * @param refundTransactionId MoPay refund transaction ID
     * @param refundHistoryId Refund history ID to update
     */
    private void pollRefundStatus(String refundTransactionId, UUID refundHistoryId) {
        logger.info("=== BACKGROUND REFUND POLLING START ===");
        logger.info("Refund Transaction ID: {}, Refund History ID: {}", refundTransactionId, refundHistoryId);
        
        int maxAttempts = 3;
        int pollIntervalSeconds = 5;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                logger.info("Refund status poll attempt {}/{} - Transaction ID: {}", attempt, maxAttempts, refundTransactionId);
                
                // Check refund status using MoPay check-status endpoint
                MoPayResponse statusResponse = moPayService.checkTransactionStatus(refundTransactionId);
                
                if (statusResponse != null) {
                    Integer statusCode = statusResponse.getStatus();
                    boolean isSuccess = (statusCode != null && (statusCode == 200 || statusCode == 201))
                        || (statusResponse.getSuccess() != null && statusResponse.getSuccess());
                    
                    logger.info("Refund status check - Transaction ID: {}, Status Code: {}, Success: {}", 
                        refundTransactionId, statusCode, isSuccess);
                    
                    // Update refund history
                    Optional<EfasheRefundHistory> refundHistoryOpt = efasheRefundHistoryRepository.findById(refundHistoryId);
                    if (refundHistoryOpt.isPresent()) {
                        EfasheRefundHistory refundHistory = refundHistoryOpt.get();
                        refundHistory.setMopayStatus(statusCode != null ? statusCode.toString() : null);
                        
                        if (isSuccess) {
                            refundHistory.setStatus("SUCCESS");
                            refundHistory.setErrorMessage(null);
                            efasheRefundHistoryRepository.save(refundHistory);
                            logger.info("✅ Refund status updated to SUCCESS - Transaction ID: {}", refundTransactionId);
                            logger.info("=== BACKGROUND REFUND POLLING END (SUCCESS) ===");
                            return; // Exit polling loop on success
                        } else {
                            // Still pending, continue polling
                            logger.info("Refund status is still PENDING - will retry in {} seconds", pollIntervalSeconds);
                        }
                    } else {
                        logger.warn("Refund history not found - ID: {}", refundHistoryId);
                        return;
                    }
                } else {
                    logger.warn("MoPay status response is null for refund transaction: {}", refundTransactionId);
                }
                
                // Wait before next attempt (except on last attempt)
                if (attempt < maxAttempts) {
                    Thread.sleep(pollIntervalSeconds * 1000);
                }
                
            } catch (InterruptedException e) {
                logger.error("Refund polling thread interrupted: ", e);
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.error("Error checking refund status (attempt {}/{}): ", attempt, maxAttempts, e);
                // Continue to next attempt even on error
            }
        }
        
        // If we reach here, all attempts failed or status is still not SUCCESS
        // Update refund history to FAILED if status is not SUCCESS
        try {
            Optional<EfasheRefundHistory> refundHistoryOpt = efasheRefundHistoryRepository.findById(refundHistoryId);
            if (refundHistoryOpt.isPresent()) {
                EfasheRefundHistory refundHistory = refundHistoryOpt.get();
                if (!"SUCCESS".equals(refundHistory.getStatus())) {
                    refundHistory.setStatus("FAILED");
                    refundHistory.setErrorMessage("Refund status check failed after " + maxAttempts + " attempts");
                    efasheRefundHistoryRepository.save(refundHistory);
                    logger.warn("⚠️ Refund status updated to FAILED after {} attempts - Transaction ID: {}", 
                        maxAttempts, refundTransactionId);
                }
            }
        } catch (Exception e) {
            logger.error("Error updating refund history to FAILED: ", e);
        }
        
        logger.info("=== BACKGROUND REFUND POLLING END (MAX ATTEMPTS REACHED) ===");
    }
    
    /**
     * Poll BizaoPayment status in the background
     * Polls every 5 seconds for 1 minute (12 attempts)
     * When status is SUCCESS, triggers EFASHE execute and sends notifications
     * 
     * @param bizaoTransactionId BizaoPayment transaction ID to poll
     * @param efasheTransactionId EFASHE transaction ID
     */
    private void pollBizaoPaymentStatus(String bizaoTransactionId, String efasheTransactionId) {
        logger.info("=== BACKGROUND BIZAO PAYMENT POLLING START ===");
        logger.info("BizaoPayment Transaction ID: {}, EFASHE Transaction ID: {}, Will poll every 5 seconds for up to 60 seconds (1 minute)", 
            bizaoTransactionId, efasheTransactionId);
        
        int maxAttempts = 12; // 60 seconds / 1 minute total (5 seconds per attempt)
        int pollIntervalSeconds = 5;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                logger.info("BizaoPayment status poll attempt {}/{} - Transaction ID: {}", attempt, maxAttempts, bizaoTransactionId);
                
                // Check BizaoPayment status
                BizaoPaymentResponse statusResponse = bizaoPaymentService.checkTransactionStatus(bizaoTransactionId);
                
                if (statusResponse != null) {
                    Integer statusCode = statusResponse.getStatus();
                    Boolean success = statusResponse.getSuccess();
                    
                    logger.info("BizaoPayment status check - Transaction ID: {}, Status Code: {}, Success: {}", 
                        bizaoTransactionId, statusCode, success);
                    
                    // Check if payment is successful
                    // Consider SUCCESS if status is 200/201 OR success flag is true
                    boolean isSuccess = (statusCode != null && (statusCode == 200 || statusCode == 201)) 
                        || (success != null && success);
                    
                    if (isSuccess) {
                        logger.info("✅ BizaoPayment transaction SUCCESS detected - Transaction ID: {}, Status: {}", 
                            bizaoTransactionId, statusCode);
                        
                        // Update transaction status
                        try {
                            Optional<EfasheTransaction> transactionOpt = efasheTransactionRepository.findByTransactionId(efasheTransactionId);
                            if (transactionOpt.isPresent()) {
                                EfasheTransaction transaction = transactionOpt.get();
                                
                                // Update BizaoPayment status
                                transaction.setMopayStatus(statusCode != null ? statusCode.toString() : "200");
                                transaction.setMopayTransactionId(bizaoTransactionId);
                                
                                // Only proceed with EFASHE execute if EFASHE status is not already SUCCESS
                                if (!"SUCCESS".equalsIgnoreCase(transaction.getEfasheStatus())) {
                                    // Trigger EFASHE execute
                                    try {
                                        EfasheExecuteRequest executeRequest = new EfasheExecuteRequest();
                                        executeRequest.setTrxId(transaction.getTrxId());
                                        executeRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                                        executeRequest.setAmount(transaction.getAmount());
                                        executeRequest.setVerticalId(getVerticalId(transaction.getServiceType()));
                                        
                                        String deliveryMethodId = transaction.getDeliveryMethodId();
                                        if (deliveryMethodId == null || deliveryMethodId.trim().isEmpty()) {
                                            deliveryMethodId = "direct_topup";
                                        }
                                        executeRequest.setDeliveryMethodId(deliveryMethodId);
                                        
                                        if ("sms".equals(deliveryMethodId)) {
                                            executeRequest.setDeliverTo(transaction.getDeliverTo());
                                        }
                                        
                                        EfasheExecuteResponse executeResponse = efasheApiService.executeTransaction(executeRequest);
                                        
                                        // Log full execute response
                                        if (executeResponse != null) {
                                            try {
                                                ObjectMapper objectMapper = new ObjectMapper();
                                                String executeResponseJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(executeResponse);
                                                logger.info("═══════════════════════════════════════════════════════════════");
                                                logger.info("📋 EFASHE EXECUTE RESPONSE (Parsed) - Webhook Handler");
                                                logger.info("═══════════════════════════════════════════════════════════════");
                                                logger.info("{}", executeResponseJson);
                                                logger.info("═══════════════════════════════════════════════════════════════");
                                            } catch (Exception logException) {
                                                logger.warn("Could not serialize execute response to JSON: {}", logException.getMessage());
                                            }
                                        }
                                        
                                        if (executeResponse != null && executeResponse.getHttpStatusCode() != null 
                                            && (executeResponse.getHttpStatusCode() == 200 || executeResponse.getHttpStatusCode() == 202)) {
                                            // Execute successful
                                            transaction.setEfasheStatus("SUCCESS");
                                            transaction.setPollEndpoint(executeResponse.getPollEndpoint());
                                            transaction.setRetryAfterSecs(executeResponse.getRetryAfterSecs());
                                            
                                            logger.info("✅ EFASHE execute successful - Transaction ID: {}, Poll Endpoint: {}", 
                                                efasheTransactionId, executeResponse.getPollEndpoint());
                                            
                                            // Send notifications
                                            sendWhatsAppNotification(transaction);
                                            
                                        } else {
                                            logger.error("❌ EFASHE execute failed - Transaction ID: {}, Response: {}", 
                                                efasheTransactionId, executeResponse);
                                            transaction.setEfasheStatus("FAILED");
                                            transaction.setErrorMessage("EFASHE execute failed after BizaoPayment success");
                                        }
                                    } catch (Exception e) {
                                        logger.error("❌ Error executing EFASHE transaction after BizaoPayment success - Transaction ID: {}", 
                                            efasheTransactionId, e);
                                        transaction.setEfasheStatus("FAILED");
                                        transaction.setErrorMessage("EFASHE execute error: " + e.getMessage());
                                    }
                                } else {
                                    logger.info("EFASHE transaction already SUCCESS - Transaction ID: {}", efasheTransactionId);
                                }
                                
                                efasheTransactionRepository.save(transaction);
                                logger.info("✅ Transaction updated after BizaoPayment SUCCESS - Transaction ID: {}", efasheTransactionId);
                            } else {
                                logger.warn("⚠️ Transaction not found for BizaoPayment polling - EFASHE Transaction ID: {}", efasheTransactionId);
                            }
                        } catch (Exception e) {
                            logger.error("Error updating transaction after BizaoPayment success: ", e);
                        }
                        
                        logger.info("=== BACKGROUND BIZAO PAYMENT POLLING END (SUCCESS) ===");
                        return;
                    } else {
                        // Check if it's explicitly failed
                        if (statusCode != null && statusCode >= 400) {
                            logger.warn("⚠️ BizaoPayment status is FAILED - Transaction ID: {}, Status Code: {}", 
                                bizaoTransactionId, statusCode);
                            
                            // Update transaction status
                            try {
                                Optional<EfasheTransaction> transactionOpt = efasheTransactionRepository.findByTransactionId(efasheTransactionId);
                                if (transactionOpt.isPresent()) {
                                    EfasheTransaction transaction = transactionOpt.get();
                                    transaction.setMopayStatus(statusCode.toString());
                                    transaction.setEfasheStatus("FAILED");
                                    transaction.setErrorMessage("BizaoPayment status failed: " + statusCode);
                                    efasheTransactionRepository.save(transaction);
                                }
                            } catch (Exception e) {
                                logger.error("Error updating transaction after BizaoPayment failure: ", e);
                            }
                            
                            logger.info("=== BACKGROUND BIZAO PAYMENT POLLING END (FAILED) ===");
                            return;
                        }
                        // Still pending, continue polling
                        logger.info("BizaoPayment status is still PENDING - will retry in {} second(s)", pollIntervalSeconds);
                    }
                } else {
                    logger.warn("BizaoPayment status response is null for transaction: {}", bizaoTransactionId);
                }
                
                // Wait before next attempt (except on last attempt)
                if (attempt < maxAttempts) {
                    Thread.sleep(pollIntervalSeconds * 1000);
                }
                
            } catch (InterruptedException e) {
                logger.error("BizaoPayment polling thread interrupted: ", e);
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.error("Error checking BizaoPayment status (attempt {}/{}): ", attempt, maxAttempts, e);
                // Continue to next attempt even on error
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(pollIntervalSeconds * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        
        // If we reach here, all attempts completed but status is still not SUCCESS
        logger.warn("⚠️ BizaoPayment status check completed but status is still not SUCCESS after {} attempts - Transaction ID: {}", 
            maxAttempts, bizaoTransactionId);
        logger.info("=== BACKGROUND BIZAO PAYMENT POLLING END (MAX ATTEMPTS REACHED) ===");
    }
    
    /**
     * Handle BizaoPayment webhook callback
     * Verifies JWT signature and processes payment status update
     * 
     * @param jwtToken JWT-encoded webhook data from BizaoPayment
     */
    @Transactional
    public void handleBizaoPaymentWebhook(String jwtToken) {
        logger.info("=== BIZAO PAYMENT WEBHOOK RECEIVED ===");
        logger.info("JWT Token: {}", jwtToken);
        
        try {
            // Verify and parse JWT token
            SecretKey signingKey = Keys.hmacShaKeyFor(bizaoPaymentWebhookSigningKey.getBytes(StandardCharsets.UTF_8));
            
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(jwtToken)
                    .getPayload();
            
            logger.info("✅ JWT token verified successfully");
            
            // Extract data from claims
            String dataJson = claims.get("data", String.class);
            if (dataJson == null || dataJson.isEmpty()) {
                throw new RuntimeException("Webhook JWT does not contain 'data' field");
            }
            
            logger.info("Webhook data JSON: {}", dataJson);
            
            // Parse the data JSON
            ObjectMapper webhookObjectMapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> webhookData = webhookObjectMapper.readValue(dataJson, java.util.Map.class);
            
            String transactionId = (String) webhookData.get("transactionId");
            Integer status = webhookData.get("status") != null ? ((Number) webhookData.get("status")).intValue() : null;
            String statusDesc = (String) webhookData.get("statusDesc");
            Object amountObj = webhookData.get("amount");
            BigDecimal amount = amountObj != null ? new BigDecimal(amountObj.toString()) : null;
            String currency = (String) webhookData.get("currency");
            String paymentType = (String) webhookData.get("paymentType");
            String transactionType = (String) webhookData.get("transactionType");
            String referenceId = (String) webhookData.get("referenceId");
            
            logger.info("Webhook data extracted - TransactionId: {}, Status: {}, StatusDesc: {}, Amount: {}, Currency: {}", 
                transactionId, status, statusDesc, amount, currency);
            
            if (transactionId == null || transactionId.isEmpty()) {
                throw new RuntimeException("Webhook data does not contain transactionId");
            }
            
            // Find transaction by BizaoPayment transaction ID
            Optional<EfasheTransaction> transactionOpt = efasheTransactionRepository.findByMopayTransactionId(transactionId);
            if (!transactionOpt.isPresent()) {
                // Try finding by EFASHE transaction ID
                transactionOpt = efasheTransactionRepository.findByTransactionId(transactionId);
            }
            
            if (!transactionOpt.isPresent()) {
                logger.warn("⚠️ Transaction not found for BizaoPayment webhook - TransactionId: {}", transactionId);
                //throw error codes 404 and 400
                throw new RuntimeException("Transaction not found for transactionId: " + transactionId);
            }
            
            EfasheTransaction transaction = transactionOpt.get();
            logger.info("Found transaction - EFASHE Transaction ID: {}, Current MoPay Status: {}, Current EFASHE Status: {}", 
                transaction.getTransactionId(), transaction.getMopayStatus(), transaction.getEfasheStatus());
            
            // Update BizaoPayment status
            if (status != null) {
                transaction.setMopayStatus(status.toString());
            }
            
            // Check if payment is successful
            boolean isSuccessful = (status != null && (status == 200 || status == 201)) 
                || "SUCCESSFUL".equalsIgnoreCase(statusDesc);
            
            if (isSuccessful && !"SUCCESS".equalsIgnoreCase(transaction.getEfasheStatus())) {
                logger.info("✅ BizaoPayment transaction SUCCESS detected - Triggering EFASHE execute...");
                
                // Execute EFASHE transaction
                try {
                    EfasheExecuteRequest executeRequest = new EfasheExecuteRequest();
                    executeRequest.setTrxId(transaction.getTrxId());
                    executeRequest.setCustomerAccountNumber(transaction.getCustomerAccountNumber());
                    executeRequest.setAmount(transaction.getAmount());
                    executeRequest.setVerticalId(getVerticalId(transaction.getServiceType()));
                    
                    String deliveryMethodId = transaction.getDeliveryMethodId();
                    if (deliveryMethodId == null || deliveryMethodId.trim().isEmpty()) {
                        deliveryMethodId = "direct_topup";
                    }
                    executeRequest.setDeliveryMethodId(deliveryMethodId);
                    
                    if ("sms".equals(deliveryMethodId)) {
                        executeRequest.setDeliverTo(transaction.getDeliverTo());
                    }
                    
                    EfasheExecuteResponse executeResponse = efasheApiService.executeTransaction(executeRequest);
                    
                    // Log full execute response
                    if (executeResponse != null) {
                        try {
                            ObjectMapper objectMapper = new ObjectMapper();
                            String executeResponseJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(executeResponse);
                            logger.info("═══════════════════════════════════════════════════════════════");
                            logger.info("📋 EFASHE EXECUTE RESPONSE (Parsed) - checkTransactionStatusWithBizao");
                            logger.info("═══════════════════════════════════════════════════════════════");
                            logger.info("{}", executeResponseJson);
                            logger.info("═══════════════════════════════════════════════════════════════");
                        } catch (Exception logException) {
                            logger.warn("Could not serialize execute response to JSON: {}", logException.getMessage());
                        }
                    }
                    
                    if (executeResponse != null && executeResponse.getHttpStatusCode() != null 
                        && (executeResponse.getHttpStatusCode() == 200 || executeResponse.getHttpStatusCode() == 202)) {
                        // Execute successful
                        transaction.setEfasheStatus("SUCCESS");
                        transaction.setPollEndpoint(executeResponse.getPollEndpoint());
                        transaction.setRetryAfterSecs(executeResponse.getRetryAfterSecs());
                        
                        logger.info("✅ EFASHE execute successful - Transaction ID: {}, Poll Endpoint: {}", 
                            transaction.getTransactionId(), executeResponse.getPollEndpoint());
                        
                        // Send notifications
                        sendWhatsAppNotification(transaction);
                        
                    } else {
                        logger.error("❌ EFASHE execute failed - Transaction ID: {}, Response: {}", 
                            transaction.getTransactionId(), executeResponse);
                        transaction.setEfasheStatus("FAILED");
                        transaction.setErrorMessage("EFASHE execute failed after BizaoPayment success");
                    }
                } catch (Exception e) {
                    logger.error("❌ Error executing EFASHE transaction after BizaoPayment success - Transaction ID: {}", 
                        transaction.getTransactionId(), e);
                    transaction.setEfasheStatus("FAILED");
                    transaction.setErrorMessage("EFASHE execute error: " + e.getMessage());
                }
            } else if (!isSuccessful) {
                // Payment failed
                logger.warn("⚠️ BizaoPayment transaction FAILED - Transaction ID: {}, Status: {}, StatusDesc: {}", 
                    transactionId, status, statusDesc);
                transaction.setEfasheStatus("FAILED");
                transaction.setErrorMessage("BizaoPayment failed - Status: " + status + ", StatusDesc: " + statusDesc);
            }
            
            // Save transaction
            efasheTransactionRepository.save(transaction);
            logger.info("✅ Transaction updated from webhook - Transaction ID: {}, MoPay Status: {}, EFASHE Status: {}", 
                transaction.getTransactionId(), transaction.getMopayStatus(), transaction.getEfasheStatus());
            
            logger.info("=== BIZAO PAYMENT WEBHOOK PROCESSED SUCCESSFULLY ===");
            
        } catch (io.jsonwebtoken.security.SecurityException | io.jsonwebtoken.ExpiredJwtException | io.jsonwebtoken.MalformedJwtException e) {
            logger.error("❌ JWT verification failed for BizaoPayment webhook: ", e);
            throw new RuntimeException("Invalid or expired JWT token: " + e.getMessage());
        } catch (Exception e) {
            logger.error("❌ Error processing BizaoPayment webhook: ", e);
            throw new RuntimeException("Failed to process webhook: " + e.getMessage());
        }
    }
    
    /**
     * Get refund history with optional filtering by receiver phone, status, and date range
     * 
     * @param receiverPhone Optional filter by receiver phone
     * @param status Optional filter by status (PENDING, SUCCESS, FAILED)
     * @param fromDate Optional start date filter
     * @param toDate Optional end date filter
     * @param page Page number
     * @param size Page size
     * @return Paginated refund history
     */
    @Transactional(readOnly = true)
    public PaginatedResponse<EfasheRefundHistoryResponse> getRefundHistory(
            String receiverPhone, String status, LocalDateTime fromDate, LocalDateTime toDate, int page, int size) {
        logger.info("Getting refund history - Receiver Phone: {}, Status: {}, From Date: {}, To Date: {}, Page: {}, Size: {}", 
            receiverPhone, status, fromDate, toDate, page, size);
        
        // Build dynamic query using EntityManager (similar to getTransactions)
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT r FROM EfasheRefundHistory r WHERE 1=1 ");
        
        // Add receiver phone filter if provided
        String normalizedPhone = null;
        if (receiverPhone != null && !receiverPhone.trim().isEmpty()) {
            normalizedPhone = normalizePhoneTo12Digits(receiverPhone.trim());
            queryBuilder.append("AND r.receiverPhone = :receiverPhone ");
        }
        
        // Add status filter if provided
        if (status != null && !status.trim().isEmpty()) {
            queryBuilder.append("AND r.status = :status ");
        }
        
        // Add date range filters if provided
        if (fromDate != null) {
            queryBuilder.append("AND r.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            queryBuilder.append("AND r.createdAt <= :toDate ");
        }
        
        queryBuilder.append("ORDER BY r.createdAt DESC");
        
        // Create query
        Query query = entityManager.createQuery(queryBuilder.toString(), EfasheRefundHistory.class);
        
        // Set parameters
        if (normalizedPhone != null) {
            query.setParameter("receiverPhone", normalizedPhone);
        }
        if (status != null && !status.trim().isEmpty()) {
            query.setParameter("status", status.trim());
        }
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        
        // Build count query for pagination
        StringBuilder countQueryBuilder = new StringBuilder();
        countQueryBuilder.append("SELECT COUNT(r) FROM EfasheRefundHistory r WHERE 1=1 ");
        
        if (normalizedPhone != null) {
            countQueryBuilder.append("AND r.receiverPhone = :receiverPhone ");
        }
        if (status != null && !status.trim().isEmpty()) {
            countQueryBuilder.append("AND r.status = :status ");
        }
        if (fromDate != null) {
            countQueryBuilder.append("AND r.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            countQueryBuilder.append("AND r.createdAt <= :toDate ");
        }
        
        Query countQuery = entityManager.createQuery(countQueryBuilder.toString(), Long.class);
        
        // Set count query parameters
        if (normalizedPhone != null) {
            countQuery.setParameter("receiverPhone", normalizedPhone);
        }
        if (status != null && !status.trim().isEmpty()) {
            countQuery.setParameter("status", status.trim());
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
        List<EfasheRefundHistory> refundHistoryList = (List<EfasheRefundHistory>) query.getResultList();
        
        // Convert to response DTOs
        List<EfasheRefundHistoryResponse> refundHistoryResponses = refundHistoryList.stream()
            .map(this::mapToRefundHistoryResponse)
            .collect(Collectors.toList());
        
        // Calculate pagination metadata
        int totalPages = (int) Math.ceil((double) totalElements / size);
        
        PaginatedResponse<EfasheRefundHistoryResponse> paginatedResponse = new PaginatedResponse<>();
        paginatedResponse.setContent(refundHistoryResponses);
        paginatedResponse.setTotalElements(totalElements);
        paginatedResponse.setTotalPages(totalPages);
        paginatedResponse.setCurrentPage(page);
        paginatedResponse.setPageSize(size);
        paginatedResponse.setFirst(page == 0);
        paginatedResponse.setLast(page >= totalPages - 1);
        
        logger.info("Retrieved {} refund history records (total: {})", refundHistoryResponses.size(), totalElements);
        return paginatedResponse;
    }
    
    /**
     * Map EfasheRefundHistory entity to EfasheRefundHistoryResponse DTO
     */
    private EfasheRefundHistoryResponse mapToRefundHistoryResponse(EfasheRefundHistory refundHistory) {
        return EfasheRefundHistoryResponse.builder()
            .id(refundHistory.getId())
            .efasheTransactionId(refundHistory.getEfasheTransaction() != null ? 
                refundHistory.getEfasheTransaction().getId() : null)
            .originalTransactionId(refundHistory.getOriginalTransactionId())
            .refundTransactionId(refundHistory.getRefundTransactionId())
            .refundAmount(refundHistory.getRefundAmount())
            .adminPhone(refundHistory.getAdminPhone())
            .receiverPhone(refundHistory.getReceiverPhone())
            .message(refundHistory.getMessage())
            .status(refundHistory.getStatus())
            .mopayStatus(refundHistory.getMopayStatus())
            .errorMessage(refundHistory.getErrorMessage())
            .createdAt(refundHistory.getCreatedAt())
            .updatedAt(refundHistory.getUpdatedAt())
            .build();
    }
}

