package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.*;
import com.pocketmoney.pocketmoney.entity.EfasheTransaction;
import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import com.pocketmoney.pocketmoney.repository.EfasheTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class EfashePaymentService {

    private static final Logger logger = LoggerFactory.getLogger(EfashePaymentService.class);

    private final EfasheSettingsService efasheSettingsService;
    private final MoPayService moPayService;
    private final EfasheApiService efasheApiService;
    private final EfasheTransactionRepository efasheTransactionRepository;

    public EfashePaymentService(EfasheSettingsService efasheSettingsService, 
                                 MoPayService moPayService,
                                 EfasheApiService efasheApiService,
                                 EfasheTransactionRepository efasheTransactionRepository) {
        this.efasheSettingsService = efasheSettingsService;
        this.moPayService = moPayService;
        this.efasheApiService = efasheApiService;
        this.efasheTransactionRepository = efasheTransactionRepository;
    }

    public EfasheInitiateResponse initiatePayment(EfasheInitiateRequest request) {
        logger.info("Initiating EFASHE payment - Service: {}, Amount: {}, Phone: {}", 
            request.getServiceType(), request.getAmount(), request.getPhone());

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

        // Build MoPay request
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        moPayRequest.setTransaction_id(transactionId);
        moPayRequest.setAmount(amount);
        moPayRequest.setCurrency(request.getCurrency());
        
        // Normalize and set customer phone (debit)
        String normalizedCustomerPhone = normalizePhoneTo12Digits(String.valueOf(request.getPhone()));
        moPayRequest.setPhone(Long.parseLong(normalizedCustomerPhone));
        moPayRequest.setPayment_mode(request.getPayment_mode());
        moPayRequest.setMessage(request.getMessage() != null ? request.getMessage() : 
            "EFASHE " + request.getServiceType() + " payment");
        moPayRequest.setCallback_url(request.getCallback_url());

        // Build transfers array - ONLY Full Amount Phone during initiate
        // Cashback transfers (Customer and Besoft) will happen AFTER validate/execute completes
        List<MoPayInitiateRequest.Transfer> transfers = new ArrayList<>();

        // Transfer 1: Full Amount Phone Number - receives amount minus customer cashback and besoft share
        // Amount: 100 - 2.00 (customer cashback) - 3.50 (besoft share) = 94.50
        // NOTE: Do NOT set transaction_id on transfers - only on main request to avoid duplicates
        MoPayInitiateRequest.Transfer transfer1 = new MoPayInitiateRequest.Transfer();
        transfer1.setAmount(fullAmountPhoneReceives);
        String normalizedFullAmountPhone = normalizePhoneTo12Digits(settingsResponse.getFullAmountPhoneNumber());
        transfer1.setPhone(Long.parseLong(normalizedFullAmountPhone));
        transfer1.setMessage("EFASHE " + request.getServiceType() + " - Full amount");
        transfers.add(transfer1);
        logger.info("Transfer 1 - Full Amount Phone: {}, Amount: {} (amount - customer cashback - besoft share)", normalizedFullAmountPhone, fullAmountPhoneReceives);
        logger.info("Cashback transfers will be sent AFTER validate/execute completes - Customer: {}, Besoft: {}", 
            customerCashbackAmount, besoftShareAmount);

        moPayRequest.setTransfers(transfers);

        // Initiate payment with MoPay
        MoPayResponse moPayResponse = moPayService.initiatePayment(moPayRequest);

        // Store transaction record in database for later status checking
        EfasheTransaction transaction = new EfasheTransaction();
        transaction.setTransactionId(transactionId);
        transaction.setServiceType(request.getServiceType());
        transaction.setCustomerPhone(normalizedCustomerPhone);
        // Convert customer phone to account number format for EFASHE
        // Remove 250 prefix and add 0 prefix: 250784638201 -> 0784638201
        String customerAccountNumber = normalizedCustomerPhone.startsWith("250") 
            ? "0" + normalizedCustomerPhone.substring(3) 
            : (normalizedCustomerPhone.startsWith("0") ? normalizedCustomerPhone : "0" + normalizedCustomerPhone);
        transaction.setCustomerAccountNumber(customerAccountNumber);
        logger.info("Customer account number for EFASHE: {} (from phone: {})", customerAccountNumber, normalizedCustomerPhone);
        transaction.setAmount(amount);
        transaction.setCurrency(request.getCurrency());
        transaction.setMopayTransactionId(moPayResponse != null ? moPayResponse.getTransactionId() : null);
        transaction.setMopayStatus(moPayResponse != null && moPayResponse.getStatus() != null 
            ? moPayResponse.getStatus().toString() : "PENDING");
        transaction.setEfasheStatus("PENDING");
        transaction.setMessage(moPayRequest.getMessage());
        
        // Store cashback amounts and phone numbers for later cashback transfers (after validate/execute)
        transaction.setCustomerCashbackAmount(customerCashbackAmount);
        transaction.setBesoftShareAmount(besoftShareAmount);
        transaction.setFullAmountPhone(settingsResponse.getFullAmountPhoneNumber());
        transaction.setCashbackPhone(settingsResponse.getCashbackPhoneNumber());
        transaction.setCashbackSent(false);
        
        // Save transaction
        efasheTransactionRepository.save(transaction);
        logger.info("Saved EFASHE transaction record - Transaction ID: {}", transactionId);

        // Build response
        EfasheInitiateResponse response = new EfasheInitiateResponse();
        response.setTransactionId(transactionId);
        response.setServiceType(request.getServiceType());
        response.setAmount(amount);
        response.setCustomerPhone(request.getPhone());
        response.setMoPayResponse(moPayResponse);
        response.setFullAmountPhone(settingsResponse.getFullAmountPhoneNumber());
        response.setCashbackPhone(settingsResponse.getCashbackPhoneNumber());
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
                        executeRequest.setDeliveryMethodId("direct_topup"); // Default for airtime
                        // deliverTo and callBack are optional for direct_topup
                        
                        logger.info("Calling EFASHE execute - TrxId: {}, Amount: {}, Account: {}", 
                            executeRequest.getTrxId(), executeRequest.getAmount(), executeRequest.getCustomerAccountNumber());
                        executeResponse = efasheApiService.executeTransaction(executeRequest);
                        
                        if (executeResponse != null) {
                            // EFASHE execute returns async response with pollEndpoint
                            transaction.setPollEndpoint(executeResponse.getPollEndpoint());
                            transaction.setRetryAfterSecs(executeResponse.getRetryAfterSecs());
                            
                            // If pollEndpoint is provided, we need to poll for status
                            if (executeResponse.getPollEndpoint() != null && !executeResponse.getPollEndpoint().isEmpty()) {
                                transaction.setEfasheStatus("PENDING");
                                transaction.setMessage("EFASHE transaction initiated. Poll endpoint: " + executeResponse.getPollEndpoint());
                                logger.info("EFASHE execute initiated async - PollEndpoint: {}, RetryAfterSecs: {}s", 
                                    executeResponse.getPollEndpoint(), executeResponse.getRetryAfterSecs());
                                
                                // Poll the status endpoint to check if transaction is SUCCESS
                                try {
                                    EfashePollStatusResponse pollResponse = efasheApiService.pollTransactionStatus(executeResponse.getPollEndpoint());
                                    
                                    if (pollResponse != null && "SUCCESS".equalsIgnoreCase(pollResponse.getStatus())) {
                                        transaction.setEfasheStatus("SUCCESS");
                                        transaction.setMessage(pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction completed successfully");
                                        logger.info("EFASHE transaction SUCCESS confirmed from poll - Status: {}", pollResponse.getStatus());
                                        
                                        // Now send cashback transfers since transaction is SUCCESS
                                        sendCashbackTransfers(transaction);
                                    } else {
                                        // Still pending or failed
                                        transaction.setEfasheStatus(pollResponse != null && pollResponse.getStatus() != null ? pollResponse.getStatus() : "PENDING");
                                        transaction.setMessage(pollResponse != null && pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction is still processing");
                                        logger.info("EFASHE transaction still pending - Status: {}", pollResponse != null ? pollResponse.getStatus() : "PENDING");
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
                                
                                // Only send cashback transfers if status is SUCCESS
                                if ("SUCCESS".equalsIgnoreCase(efasheStatus)) {
                                    logger.info("EFASHE transaction SUCCESS confirmed, sending cashback transfers");
                                    sendCashbackTransfers(transaction);
                                } else {
                                    logger.info("EFASHE transaction status is not SUCCESS: {}, cashback transfers will not be sent", efasheStatus);
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
                }
            } else {
                logger.info("MoPay transaction not yet SUCCESS or already processed - Status: {}, EFASHE Status: {}", 
                    statusCode, transaction.getEfasheStatus());
                
                // If EFASHE is PENDING with pollEndpoint, check status again
                if ("PENDING".equalsIgnoreCase(transaction.getEfasheStatus()) 
                    && transaction.getPollEndpoint() != null 
                    && !transaction.getPollEndpoint().isEmpty()
                    && (transaction.getCashbackSent() == null || !transaction.getCashbackSent())) {
                    logger.info("Checking EFASHE poll status for pending transaction - PollEndpoint: {}", transaction.getPollEndpoint());
                    try {
                        EfashePollStatusResponse pollResponse = efasheApiService.pollTransactionStatus(transaction.getPollEndpoint());
                        
                        if (pollResponse != null && "SUCCESS".equalsIgnoreCase(pollResponse.getStatus())) {
                            transaction.setEfasheStatus("SUCCESS");
                            transaction.setMessage(pollResponse.getMessage() != null ? pollResponse.getMessage() : "EFASHE transaction completed successfully");
                            logger.info("EFASHE transaction SUCCESS confirmed from poll - Status: {}", pollResponse.getStatus());
                            
                            // Now send cashback transfers since transaction is SUCCESS
                            sendCashbackTransfers(transaction);
                        } else if (pollResponse != null && pollResponse.getStatus() != null) {
                            // Update status (could be FAILED or still PENDING)
                            transaction.setEfasheStatus(pollResponse.getStatus());
                            transaction.setMessage(pollResponse.getMessage());
                            logger.info("EFASHE poll status updated - Status: {}", pollResponse.getStatus());
                        }
                    } catch (Exception e) {
                        logger.warn("Error polling EFASHE status for pending transaction: ", e);
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
        
        logger.info("Transaction status check result - Transaction ID: {}, MoPay Status: {}, EFASHE Status: {}", 
            transactionId, transaction.getMopayStatus(), transaction.getEfasheStatus());
        return response;
    }
    
    /**
     * Send cashback transfers to customer and besoft phone after EFASHE execute completes
     * This creates separate MoPay payment requests for each cashback transfer
     */
    private void sendCashbackTransfers(EfasheTransaction transaction) {
        if (transaction.getCashbackSent() != null && transaction.getCashbackSent()) {
            logger.info("Cashback transfers already sent for transaction: {}", transaction.getTransactionId());
            return;
        }
        
        if (transaction.getCustomerCashbackAmount() == null || transaction.getBesoftShareAmount() == null) {
            logger.warn("Cashback amounts not set for transaction: {}", transaction.getTransactionId());
            return;
        }
        
        try {
            String normalizedFullAmountPhone = normalizePhoneTo12Digits(transaction.getFullAmountPhone());
            String normalizedCustomerPhone = normalizePhoneTo12Digits(transaction.getCustomerPhone());
            String normalizedBesoftPhone = normalizePhoneTo12Digits(transaction.getCashbackPhone());
            
            // Send customer cashback transfer
            if (transaction.getCustomerCashbackAmount().compareTo(BigDecimal.ZERO) > 0) {
                sendCashbackTransfer(
                    normalizedFullAmountPhone,
                    normalizedCustomerPhone,
                    transaction.getCustomerCashbackAmount(),
                    "EFASHE " + transaction.getServiceType() + " - Customer cashback",
                    "CustomerCashback"
                );
                logger.info("Customer cashback transfer sent - Amount: {}, To: {}", 
                    transaction.getCustomerCashbackAmount(), normalizedCustomerPhone);
            }
            
            // Send besoft share transfer
            if (transaction.getBesoftShareAmount().compareTo(BigDecimal.ZERO) > 0) {
                sendCashbackTransfer(
                    normalizedFullAmountPhone,
                    normalizedBesoftPhone,
                    transaction.getBesoftShareAmount(),
                    "EFASHE " + transaction.getServiceType() + " - Besoft share",
                    "BesoftShare"
                );
                logger.info("Besoft share transfer sent - Amount: {}, To: {}", 
                    transaction.getBesoftShareAmount(), normalizedBesoftPhone);
            }
            
            // Mark cashback as sent
            transaction.setCashbackSent(true);
            efasheTransactionRepository.save(transaction);
            logger.info("Cashback transfers completed for transaction: {}", transaction.getTransactionId());
        } catch (Exception e) {
            logger.error("Error sending cashback transfers for transaction {}: ", transaction.getTransactionId(), e);
            // Don't fail the whole transaction if cashback transfer fails
        }
    }
    
    /**
     * Send a single cashback transfer via MoPay
     * Creates a new MoPay payment from Full Amount Phone to recipient
     */
    private void sendCashbackTransfer(String fromPhone, String toPhone, BigDecimal amount, String message, String transferType) {
        try {
            // Generate a unique transaction ID for the cashback transfer
            String cashbackTransactionId = generateEfasheTransactionId() + "-" + transferType;
            
            // Build MoPay request for cashback transfer
            MoPayInitiateRequest cashbackRequest = new MoPayInitiateRequest();
            cashbackRequest.setTransaction_id(cashbackTransactionId);
            cashbackRequest.setAmount(amount);
            cashbackRequest.setCurrency("RWF");
            cashbackRequest.setPhone(Long.parseLong(fromPhone));
            cashbackRequest.setPayment_mode("MOBILE");
            cashbackRequest.setMessage(message);
            
            // Create transfer to recipient
            List<MoPayInitiateRequest.Transfer> transfers = new ArrayList<>();
            MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
            transfer.setAmount(amount);
            transfer.setPhone(Long.parseLong(toPhone));
            transfer.setMessage(message);
            transfers.add(transfer);
            cashbackRequest.setTransfers(transfers);
            
            // Initiate the cashback transfer
            MoPayResponse cashbackResponse = moPayService.initiatePayment(cashbackRequest);
            
            if (cashbackResponse != null && cashbackResponse.getSuccess() != null && cashbackResponse.getSuccess()) {
                logger.info("Cashback transfer initiated successfully - Type: {}, Amount: {}, From: {}, To: {}, Transaction ID: {}", 
                    transferType, amount, fromPhone, toPhone, cashbackResponse.getTransactionId());
            } else {
                logger.error("Cashback transfer failed - Type: {}, Amount: {}, From: {}, To: {}, Response: {}", 
                    transferType, amount, fromPhone, toPhone, cashbackResponse);
            }
        } catch (Exception e) {
            logger.error("Error initiating cashback transfer - Type: {}, Amount: {}, From: {}, To: {}: ", 
                transferType, amount, fromPhone, toPhone, e);
            throw e;
        }
    }
    
    /**
     * Convert EfasheServiceType to EFASHE verticalId
     */
    private String getVerticalId(EfasheServiceType serviceType) {
        if (serviceType == null) {
            return "airtime"; // Default
        }
        switch (serviceType) {
            case AIRTIME:
                return "airtime";
            case MTN:
                return "airtime"; // MTN is also airtime
            case RRA:
                return "rra";
            case TV:
                return "tv";
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
}

