package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class EfashePaymentService {

    private static final Logger logger = LoggerFactory.getLogger(EfashePaymentService.class);

    private final EfasheSettingsService efasheSettingsService;
    private final MoPayService moPayService;

    public EfashePaymentService(EfasheSettingsService efasheSettingsService, MoPayService moPayService) {
        this.efasheSettingsService = efasheSettingsService;
        this.moPayService = moPayService;
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

        // Build transfers array
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

        // Transfer 2: Customer phone (customer gets cashback back - 2.00)
        MoPayInitiateRequest.Transfer transfer2 = new MoPayInitiateRequest.Transfer();
        transfer2.setAmount(customerCashbackAmount);
        transfer2.setPhone(Long.parseLong(normalizedCustomerPhone));
        transfer2.setMessage("EFASHE " + request.getServiceType() + " - Customer cashback");
        transfers.add(transfer2);
        logger.info("Transfer 2 - Customer Phone: {}, Amount: {} (customer cashback)", normalizedCustomerPhone, customerCashbackAmount);

        // Transfer 3: Besoft phone (Besoft share - 3.50)
        // Using Cashback Phone Number for Besoft share as per settings structure
        MoPayInitiateRequest.Transfer transfer3 = new MoPayInitiateRequest.Transfer();
        transfer3.setAmount(besoftShareAmount);
        String normalizedBesoftPhone = normalizePhoneTo12Digits(settingsResponse.getCashbackPhoneNumber());
        transfer3.setPhone(Long.parseLong(normalizedBesoftPhone));
        transfer3.setMessage("EFASHE " + request.getServiceType() + " - Besoft share");
        transfers.add(transfer3);
        logger.info("Transfer 3 - Besoft Phone: {}, Amount: {} (besoft share)", normalizedBesoftPhone, besoftShareAmount);

        moPayRequest.setTransfers(transfers);

        // Initiate payment with MoPay
        MoPayResponse moPayResponse = moPayService.initiatePayment(moPayRequest);

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
     * @param transactionId The EFASHE transaction ID
     * @return MoPayResponse containing the transaction status
     */
    public MoPayResponse checkTransactionStatus(String transactionId) {
        logger.info("Checking EFASHE transaction status for transaction ID: {}", transactionId);
        MoPayResponse response = moPayService.checkTransactionStatus(transactionId);
        logger.info("Transaction status check result - Transaction ID: {}, Status: {}, Success: {}", 
            transactionId, response != null ? response.getStatus() : "null", 
            response != null ? response.getSuccess() : "null");
        return response;
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

