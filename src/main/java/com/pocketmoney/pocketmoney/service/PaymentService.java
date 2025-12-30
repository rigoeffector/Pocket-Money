package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.*;
import com.pocketmoney.pocketmoney.entity.PaymentCategory;
import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import com.pocketmoney.pocketmoney.entity.Transaction;
import com.pocketmoney.pocketmoney.entity.TransactionStatus;
import com.pocketmoney.pocketmoney.entity.TransactionType;
import com.pocketmoney.pocketmoney.entity.User;
import com.pocketmoney.pocketmoney.entity.UserStatus;
import com.pocketmoney.pocketmoney.repository.BalanceAssignmentHistoryRepository;
import com.pocketmoney.pocketmoney.repository.PaymentCategoryRepository;
import com.pocketmoney.pocketmoney.repository.ReceiverRepository;
import com.pocketmoney.pocketmoney.repository.TransactionRepository;
import com.pocketmoney.pocketmoney.repository.UserRepository;
import com.pocketmoney.pocketmoney.repository.PaymentCommissionSettingRepository;
import com.pocketmoney.pocketmoney.entity.PaymentCommissionSetting;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final PaymentCategoryRepository paymentCategoryRepository;
    private final ReceiverRepository receiverRepository;
    private final BalanceAssignmentHistoryRepository balanceAssignmentHistoryRepository;
    private final PaymentCommissionSettingRepository paymentCommissionSettingRepository;
    private final MoPayService moPayService;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;
    private final MessagingService messagingService;
    private final WhatsAppService whatsAppService;

    public PaymentService(UserRepository userRepository, TransactionRepository transactionRepository,
                         PaymentCategoryRepository paymentCategoryRepository, ReceiverRepository receiverRepository,
                         BalanceAssignmentHistoryRepository balanceAssignmentHistoryRepository,
                         PaymentCommissionSettingRepository paymentCommissionSettingRepository,
                         MoPayService moPayService, PasswordEncoder passwordEncoder, EntityManager entityManager,
                         MessagingService messagingService, WhatsAppService whatsAppService) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.paymentCategoryRepository = paymentCategoryRepository;
        this.receiverRepository = receiverRepository;
        this.balanceAssignmentHistoryRepository = balanceAssignmentHistoryRepository;
        this.paymentCommissionSettingRepository = paymentCommissionSettingRepository;
        this.moPayService = moPayService;
        this.passwordEncoder = passwordEncoder;
        this.entityManager = entityManager;
        this.messagingService = messagingService;
        this.whatsAppService = whatsAppService;
    }

    // Helper method to normalize phone number to 12 digits (250XXXXXXXXX format)
    private String normalizePhoneTo12Digits(String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            throw new RuntimeException("Phone number cannot be null or empty");
        }
        
        String cleaned = phone.replaceAll("[^0-9]", "");
        
        // If exactly 12 digits and starts with 250, return as is
        if (cleaned.length() == 12 && cleaned.startsWith("250")) {
            return cleaned;
        }
        
        // If starts with 250, extract the 9-digit number part
        if (cleaned.startsWith("250")) {
            String without250 = cleaned.substring(3);
            // If we have exactly 9 digits after 250, we're good
            if (without250.length() == 9) {
                return "250" + without250;
            }
            // If more than 9 digits, take the last 9
            if (without250.length() > 9) {
                String last9 = without250.substring(without250.length() - 9);
                return "250" + last9;
            }
            // If less than 9, this is an error case
            throw new RuntimeException("Invalid phone format after 250 prefix. Expected 9 digits, got: " + without250.length() + " (phone: " + phone + ")");
        }
        
        // If starts with 0, remove it first
        if (cleaned.startsWith("0")) {
            cleaned = cleaned.substring(1);
        }
        
        // Now cleaned should be 9 or 10 digits (or potentially 11 if original was malformed)
        // Extract the last 9 digits to ensure we get the correct format
        if (cleaned.length() >= 9) {
            String last9 = cleaned.substring(Math.max(0, cleaned.length() - 9));
            return "250" + last9;
        }
        
        // If less than 9 digits, pad with leading zeros
        String padded = cleaned;
        while (padded.length() < 9) {
            padded = "0" + padded;
        }
        return "250" + padded;
    }

    public PaymentResponse topUp(String nfcCardId, TopUpRequest request) {
        // Find user by NFC card ID
        User user = userRepository.findByNfcCardId(nfcCardId)
                .orElseThrow(() -> new RuntimeException("User not found with NFC card ID: " + nfcCardId));
        
        // Verify that the user has this NFC card assigned
        if (user.getIsAssignedNfcCard() == null || !user.getIsAssignedNfcCard() 
                || user.getNfcCardId() == null || !user.getNfcCardId().equals(nfcCardId)) {
            throw new RuntimeException("NFC card is not assigned to this user");
        }
        
        // Verify user is active
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new RuntimeException("User account is not active. Status: " + user.getStatus());
        }

        // Create MoPay initiate request
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        moPayRequest.setAmount(request.getAmount());
        moPayRequest.setCurrency("RWF");
        // Normalize phone to 12 digits and convert to Long (MoPay API requires 12 digits)
        String normalizedPayerPhone = normalizePhoneTo12Digits(request.getPhone());
        moPayRequest.setPhone(Long.parseLong(normalizedPayerPhone));
        moPayRequest.setPayment_mode("MOBILE");
        moPayRequest.setMessage(request.getMessage() != null ? request.getMessage() : "Top up to pocket money card");

        // Create transfer to fixed receiver (always 250794230137)
        MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
        transfer.setAmount(request.getAmount());
        // Receiver phone is always 250794230137
        transfer.setPhone(250794230137L);
        transfer.setMessage(request.getMessage() != null ? request.getMessage() : "Top up to pocket money card");
        moPayRequest.setTransfers(java.util.List.of(transfer));

        // Initiate payment with MoPay
        MoPayResponse moPayResponse = moPayService.initiatePayment(moPayRequest);

        // Create transaction record
        Transaction transaction = new Transaction();
        transaction.setUser(user);
        transaction.setTransactionType(TransactionType.TOP_UP);
        transaction.setAmount(request.getAmount());
        transaction.setPhoneNumber(request.getPhone());
        transaction.setMessage(moPayRequest.getMessage());
        transaction.setBalanceBefore(user.getAmountRemaining());

        // Check MoPay response - API returns status 201 and transactionId on success
        String transactionId = moPayResponse != null ? moPayResponse.getTransactionId() : null;
        if (moPayResponse != null && moPayResponse.getStatus() != null && moPayResponse.getStatus() == 201 
            && transactionId != null) {
            // Successfully initiated - store transaction ID and set as PENDING
            transaction.setMopayTransactionId(transactionId);
            transaction.setStatus(TransactionStatus.PENDING);
        } else {
            // Initiation failed - mark as FAILED with error message
            transaction.setStatus(TransactionStatus.FAILED);
            String errorMessage = moPayResponse != null && moPayResponse.getMessage() != null 
                ? moPayResponse.getMessage() 
                : "Payment initiation failed - status: " + (moPayResponse != null ? moPayResponse.getStatus() : "null") 
                    + ", transactionId: " + transactionId;
            transaction.setMessage(errorMessage);
        }

        // DO NOT update balance here - balance will be updated when status is checked and becomes SUCCESS
        Transaction savedTransaction = transactionRepository.save(transaction);
        return mapToPaymentResponse(savedTransaction);
    }

    public PaymentResponse topUpByPhone(TopUpByPhoneRequest request) {
        // Find user by NFC card ID (this will fetch user information including phone number)
        User user = userRepository.findByNfcCardId(request.getNfcCardId())
                .orElseThrow(() -> new RuntimeException("User not found with NFC card ID: " + request.getNfcCardId()));
        
        // Verify that the user has this NFC card assigned
        if (user.getIsAssignedNfcCard() == null || !user.getIsAssignedNfcCard() 
                || user.getNfcCardId() == null || !user.getNfcCardId().equals(request.getNfcCardId())) {
            throw new RuntimeException("NFC card is not assigned to this user");
        }
        
        // Verify user is active
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new RuntimeException("User account is not active. Status: " + user.getStatus());
        }

        // Create a TopUpRequest to reuse existing topUp logic
        TopUpRequest topUpRequest = new TopUpRequest();
        topUpRequest.setNfcCardId(request.getNfcCardId());
        topUpRequest.setAmount(request.getAmount());
        topUpRequest.setPhone(request.getPayerPhone());
        topUpRequest.setMessage(request.getMessage() != null ? request.getMessage() : "Top up to pocket money card");

        // Use the existing topUp method with the NFC card ID
        return topUp(request.getNfcCardId(), topUpRequest);
    }

    public PaymentResponse makePayment(UUID userId, PaymentRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Verify payment category exists
        PaymentCategory paymentCategory = paymentCategoryRepository.findById(request.getPaymentCategoryId())
                .orElseThrow(() -> new RuntimeException("Payment category not found"));

        if (!paymentCategory.getIsActive()) {
            throw new RuntimeException("Payment category is not active");
        }

        // Verify receiver exists and is active
        Receiver receiver = receiverRepository.findById(request.getReceiverId())
                .orElseThrow(() -> new RuntimeException("Receiver not found"));

        if (receiver.getStatus() != ReceiverStatus.ACTIVE) {
            throw new RuntimeException("Receiver is not active. Status: " + receiver.getStatus());
        }
        
        // Determine the balance owner: if receiver is a submerchant, use parent's balance
        Receiver balanceOwner = receiver.getParentReceiver() != null ? receiver.getParentReceiver() : receiver;

        // Verify PIN
        if (!passwordEncoder.matches(request.getPin(), user.getPin())) {
            throw new RuntimeException("Invalid PIN");
        }

        // Check balance
        BigDecimal balanceBefore = user.getAmountRemaining();
        if (balanceBefore.compareTo(request.getAmount()) < 0) {
            throw new RuntimeException("Insufficient balance. Available: " + balanceBefore);
        }

        BigDecimal paymentAmount = request.getAmount();
        
        // Calculate discount and bonus amounts (use balance owner's percentages)
        BigDecimal discountPercentage = balanceOwner.getDiscountPercentage() != null ? balanceOwner.getDiscountPercentage() : BigDecimal.ZERO;
        BigDecimal userBonusPercentage = balanceOwner.getUserBonusPercentage() != null ? balanceOwner.getUserBonusPercentage() : BigDecimal.ZERO;
        
        // Calculate discount/charge amount (based on payment amount) - This is the TOTAL charge (e.g., 10%)
        BigDecimal discountAmount = paymentAmount.multiply(discountPercentage).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        
        // Calculate user bonus amount (e.g., 2% of payment amount)
        BigDecimal userBonusAmount = paymentAmount.multiply(userBonusPercentage).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        
        // Calculate admin income amount (e.g., 8% = 10% - 2%)
        BigDecimal adminIncomeAmount = discountAmount.subtract(userBonusAmount);
        
        // Use balance owner's remaining balance (main merchant if submerchant, otherwise receiver itself)
        // Receiver's remaining balance is deducted by ONLY the payment amount (discount was already added as bonus when balance was assigned)
        // Example: User pays 500, deduct only 500 from receiver balance (no discount deduction)
        // Check if balance owner has sufficient remaining balance BEFORE processing payment
        BigDecimal receiverBalanceBefore = balanceOwner.getRemainingBalance() != null ? balanceOwner.getRemainingBalance() : BigDecimal.ZERO;
        BigDecimal receiverBalanceReduction = paymentAmount; // Only deduct payment amount, not payment + discount
        BigDecimal receiverBalanceAfter = receiverBalanceBefore.subtract(receiverBalanceReduction);
        
        // Check if remaining balance would be below 1 after this transaction
        if (receiverBalanceAfter.compareTo(new BigDecimal("1")) < 0) {
            throw new RuntimeException("Contact BeFosot to top up for your company. Your card has sufficient balance, but the company's selling balance is low.");
        }
        
        // For PAYMENT: Direct internal transfer (no MoPay integration needed)
        // Deduct from user's card balance (amountRemaining)
        BigDecimal userNewBalance = balanceBefore.subtract(paymentAmount);
        user.setAmountRemaining(userNewBalance);
        
        // Add user bonus back to user's wallet if applicable
        if (userBonusAmount.compareTo(BigDecimal.ZERO) > 0) {
            userNewBalance = userNewBalance.add(userBonusAmount);
            user.setAmountRemaining(userNewBalance);
        }
        user.setLastTransactionDate(LocalDateTime.now());
        userRepository.save(user);

        // Credit receiver's wallet balance (full payment amount) - individual receiver's wallet
        BigDecimal receiverNewBalance = receiver.getWalletBalance().add(paymentAmount);
        BigDecimal receiverNewTotal = receiver.getTotalReceived().add(paymentAmount);
        receiver.setWalletBalance(receiverNewBalance);
        receiver.setTotalReceived(receiverNewTotal);
        receiver.setLastTransactionDate(LocalDateTime.now());
        receiverRepository.save(receiver);
        
        // Update balance owner's remaining balance (shared balance - main merchant's if submerchant)
        // Balance owner's remaining balance is reduced by ONLY the payment amount
        // The discount percentage was already added as a bonus when balance was assigned
        // (Already calculated and validated above - receiverBalanceAfter is safe to use)
        balanceOwner.setRemainingBalance(receiverBalanceAfter);
        balanceOwner.setLastTransactionDate(LocalDateTime.now());
        receiverRepository.save(balanceOwner);
        
        // Also update balance owner's wallet balance and total received (shared)
        BigDecimal balanceOwnerNewWallet = balanceOwner.getWalletBalance().add(paymentAmount);
        BigDecimal balanceOwnerNewTotal = balanceOwner.getTotalReceived().add(paymentAmount);
        balanceOwner.setWalletBalance(balanceOwnerNewWallet);
        balanceOwner.setTotalReceived(balanceOwnerNewTotal);
        receiverRepository.save(balanceOwner);

        // Create transaction record - PAYMENT is immediate (no MoPay, internal transfer)
        Transaction transaction = new Transaction();
        transaction.setUser(user);
        transaction.setPaymentCategory(paymentCategory);
        transaction.setReceiver(receiver);
        transaction.setTransactionType(TransactionType.PAYMENT);
        transaction.setAmount(paymentAmount);
        transaction.setPhoneNumber(receiver.getReceiverPhone());
        transaction.setMessage(request.getMessage() != null ? request.getMessage() : "Payment from pocket money card");
        transaction.setBalanceBefore(balanceBefore); // User balance before deduction
        transaction.setBalanceAfter(userNewBalance); // User balance after deduction and bonus
        transaction.setDiscountAmount(discountAmount);
        transaction.setUserBonusAmount(userBonusAmount);
        transaction.setAdminIncomeAmount(adminIncomeAmount);
        transaction.setReceiverBalanceBefore(receiverBalanceBefore); // Balance owner's balance before
        transaction.setReceiverBalanceAfter(receiverBalanceAfter); // Balance owner's balance after
        transaction.setStatus(TransactionStatus.SUCCESS); // Immediate success for internal transfers

        Transaction savedTransaction = transactionRepository.save(transaction);
        
        // Send SMS and WhatsApp notifications to user and receiver
        try {
            // Send SMS and WhatsApp to user (NFC Card payment)
            String userPhoneNormalized = normalizePhoneTo12Digits(user.getPhoneNumber());
            
            // Build message with bonus information if applicable
            String userSmsMessage;
            String userWhatsAppMessage;
            String userPayerName = (user.getFullNames() != null && !user.getFullNames().trim().isEmpty()) 
                ? user.getFullNames() 
                : user.getPhoneNumber();
            
            if (userBonusAmount.compareTo(BigDecimal.ZERO) > 0) {
                // Include bonus amount in message
                userSmsMessage = String.format("Payment of %s RWF to %s successful. Bonus: %s RWF returned. New balance: %s RWF", 
                    paymentAmount.toPlainString(), receiver.getCompanyName(), userBonusAmount.toPlainString(), userNewBalance.toPlainString());
                userWhatsAppMessage = String.format("[%s]: Paid %s RWF to [%s] via Card. Bonus: %s RWF returned.",
                    userPayerName, paymentAmount.toPlainString(), receiver.getCompanyName(), userBonusAmount.toPlainString());
            } else {
                // No bonus
                userSmsMessage = String.format("Payment of %s RWF to %s successful. New balance: %s RWF", 
                    paymentAmount.toPlainString(), receiver.getCompanyName(), userNewBalance.toPlainString());
                userWhatsAppMessage = String.format("[%s]: Paid %s RWF to [%s] via Card.",
                    userPayerName, paymentAmount.toPlainString(), receiver.getCompanyName());
            }
            
            messagingService.sendSms(userSmsMessage, userPhoneNormalized);
            whatsAppService.sendWhatsApp(userWhatsAppMessage, userPhoneNormalized);
            logger.info("SMS sent to user {}: {}", userPhoneNormalized, userSmsMessage);
            logger.info("WhatsApp sent to user {}: {}", userPhoneNormalized, userWhatsAppMessage);
            
            // Send SMS and WhatsApp to receiver - same format
            String receiverPhoneNormalized = normalizePhoneTo12Digits(receiver.getReceiverPhone());
            String receiverSmsMessage = String.format("Received %s RWF from %s", 
                paymentAmount.toPlainString(), userPayerName);
            String receiverWhatsAppMessage = String.format("[%s]: Paid %s RWF to [%s] via Card.",
                userPayerName, paymentAmount.toPlainString(), receiver.getCompanyName());
            messagingService.sendSms(receiverSmsMessage, receiverPhoneNormalized);
            whatsAppService.sendWhatsApp(receiverWhatsAppMessage, receiverPhoneNormalized);
            logger.info("SMS and WhatsApp sent to receiver {} about payment", receiverPhoneNormalized);
        } catch (Exception e) {
            logger.error("Failed to send payment SMS/WhatsApp notifications: ", e);
            // Don't fail the payment if SMS/WhatsApp fails
        }
        
        return mapToPaymentResponse(savedTransaction);
    }

    public PaymentResponse makeMomoPayment(MomoPaymentRequest request) {
        // User is optional for MOMO payments (guests can pay without an account)
        User user = null;
        if (request.getUserId() != null) {
            user = userRepository.findById(request.getUserId())
                    .orElseThrow(() -> new RuntimeException("User not found"));
        }

        // Verify payment category exists
        PaymentCategory paymentCategory = paymentCategoryRepository.findById(request.getPaymentCategoryId())
                .orElseThrow(() -> new RuntimeException("Payment category not found"));

        if (!paymentCategory.getIsActive()) {
            throw new RuntimeException("Payment category is not active");
        }

        // Verify receiver exists and is active
        Receiver receiver = receiverRepository.findById(request.getReceiverId())
                .orElseThrow(() -> new RuntimeException("Receiver not found"));

        if (receiver.getStatus() != ReceiverStatus.ACTIVE) {
            throw new RuntimeException("Receiver is not active. Status: " + receiver.getStatus());
        }
        
        // Determine the balance owner: if receiver is a submerchant, use parent's balance
        Receiver balanceOwner = receiver.getParentReceiver() != null ? receiver.getParentReceiver() : receiver;

        // Note: PIN is not required for MOMO payments as they are authenticated via MoPay
        // The payment is initiated from the payer's MOMO wallet which requires their own authentication

        // Determine payer phone number: must be provided if userId is not provided
        String payerPhone = request.getPayerPhone();
        if (payerPhone == null || payerPhone.trim().isEmpty()) {
            if (user != null && user.getPhoneNumber() != null && !user.getPhoneNumber().trim().isEmpty()) {
                // Use user's default phone number if available
                payerPhone = user.getPhoneNumber();
            } else {
                throw new RuntimeException("Payer phone number is required for MOMO payment when user ID is not provided.");
            }
        } else {
            // Validate the provided phone number format
            payerPhone = payerPhone.trim();
            if (!payerPhone.matches("^[0-9]{10,15}$")) {
                throw new RuntimeException("Invalid phone number format. Phone number must be between 10 and 15 digits.");
            }
        }

        BigDecimal paymentAmount = request.getAmount();
        
        // Calculate discount and bonus amounts (use balance owner's percentages)
        BigDecimal discountPercentage = balanceOwner.getDiscountPercentage() != null ? balanceOwner.getDiscountPercentage() : BigDecimal.ZERO;
        BigDecimal userBonusPercentage = balanceOwner.getUserBonusPercentage() != null ? balanceOwner.getUserBonusPercentage() : BigDecimal.ZERO;
        
        // Get active commission settings for the receiver
        List<PaymentCommissionSetting> activeCommissionSettings = paymentCommissionSettingRepository.findByReceiverIdAndIsActiveTrue(receiver.getId());
        BigDecimal commissionPercentage = BigDecimal.ZERO;
        String commissionPhoneNumber = null;
        
        // If there are active commission settings, use the first one (or sum if multiple)
        // For now, we'll use the first active commission setting
        if (!activeCommissionSettings.isEmpty()) {
            PaymentCommissionSetting commissionSetting = activeCommissionSettings.get(0);
            commissionPercentage = commissionSetting.getCommissionPercentage();
            commissionPhoneNumber = commissionSetting.getPhoneNumber();
            logger.info("Found active commission setting - Percentage: {}%, Phone: {}", commissionPercentage, commissionPhoneNumber);
        }
        
        // Calculate discount/charge amount (based on payment amount) - This is the TOTAL charge (e.g., 10%)
        BigDecimal discountAmount = paymentAmount.multiply(discountPercentage).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        
        // Calculate user bonus amount (e.g., 2% of payment amount)
        BigDecimal userBonusAmount = paymentAmount.multiply(userBonusPercentage).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        
        // Calculate commission amount (e.g., 1% of payment amount)
        BigDecimal commissionAmount = paymentAmount.multiply(commissionPercentage).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        
        // Calculate admin income amount (e.g., 7% = 10% - 2% - 1%)
        BigDecimal adminIncomeAmount = discountAmount.subtract(userBonusAmount).subtract(commissionAmount);
        
        // Note: For MOMO payments, we don't check receiver balance here because:
        // 1. Payment comes from user's external MOMO wallet, not from system balance
        // 2. Receiver balance will be updated after MOMO payment is confirmed (in checkTransactionStatus)
        // 3. Balance check is only needed for internal payments (makePayment method)
        BigDecimal receiverBalanceBefore = balanceOwner.getRemainingBalance() != null ? balanceOwner.getRemainingBalance() : BigDecimal.ZERO;
        BigDecimal receiverBalanceReduction = paymentAmount; // Only deduct payment amount
        BigDecimal receiverBalanceAfter = receiverBalanceBefore.subtract(receiverBalanceReduction);
        
        // Store the calculated balance after for later use when payment is confirmed
        // (will be applied in checkTransactionStatus when status becomes SUCCESS)

        // Create MoPay initiate request
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        moPayRequest.setAmount(paymentAmount);
        moPayRequest.setCurrency("RWF");
        
        // Normalize payer phone to 12 digits and convert to Long (MoPay API requires 12 digits)
        String normalizedPayerPhone = normalizePhoneTo12Digits(payerPhone);
        
        logger.info("Payer phone normalization - Original: '{}', Normalized: '{}' ({} digits)", payerPhone, normalizedPayerPhone, normalizedPayerPhone.length());
        
        // Validate the normalized phone is exactly 12 digits
        if (normalizedPayerPhone.length() != 12) {
            throw new RuntimeException("Payer phone number must be normalized to exactly 12 digits. Got: " + normalizedPayerPhone.length() + " digits: " + normalizedPayerPhone);
        }
        
        // Validate it's numeric only
        if (!normalizedPayerPhone.matches("^[0-9]{12}$")) {
            throw new RuntimeException("Normalized payer phone must contain only digits. Got: '" + normalizedPayerPhone + "'");
        }
        
        Long payerPhoneLong = Long.parseLong(normalizedPayerPhone);
        logger.info("Setting payer phone in MoPay request to: {} (Long value)", payerPhoneLong);
        moPayRequest.setPhone(payerPhoneLong);
        moPayRequest.setPayment_mode("MOBILE");
        moPayRequest.setMessage(request.getMessage() != null ? request.getMessage() : "Payment to " + receiver.getCompanyName());
        
        // Create transfer to admin (hardcoded phone number) - amount minus user bonus and commission
        // The remaining amount (paymentAmount - userBonusAmount - commissionAmount) goes to hardcoded admin phone
        BigDecimal adminAmount = paymentAmount.subtract(userBonusAmount).subtract(commissionAmount);
        
        logger.info("=== MOMO PAYMENT TRANSFER CALCULATION ===");
        logger.info("Payment amount: {}, User bonus amount: {}, Commission amount: {}, Admin amount (to hardcoded phone): {}", 
                paymentAmount, userBonusAmount, commissionAmount, adminAmount);
        
        MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
        transfer.setAmount(adminAmount);
        // Always use hardcoded admin phone number (250794230137) for receiving MOMO payments
        String adminPhone = "250794230137";
        logger.info("Using hardcoded admin phone for receiving payment: {}", adminPhone);
        
        // Normalize admin phone to 12 digits - ensure it's exactly 12 digits
        String normalizedAdminPhone = normalizePhoneTo12Digits(adminPhone);
        
        logger.info("Admin phone normalization - Original: '{}', Normalized: '{}' ({} digits)", adminPhone, normalizedAdminPhone, normalizedAdminPhone.length());
        
        // Validate the normalized phone is exactly 12 digits
        if (normalizedAdminPhone.length() != 12) {
            throw new RuntimeException("Admin phone number must be normalized to exactly 12 digits. Original: '" + adminPhone + "', Normalized: '" + normalizedAdminPhone + "' (" + normalizedAdminPhone.length() + " digits)");
        }
        
        // Validate it's numeric only
        if (!normalizedAdminPhone.matches("^[0-9]{12}$")) {
            throw new RuntimeException("Normalized admin phone must contain only digits. Got: '" + normalizedAdminPhone + "'");
        }
        
        Long adminPhoneLong = Long.parseLong(normalizedAdminPhone);
        logger.info("Setting admin phone in transfer to: {} (Long value), Amount: {} (payment {} minus bonus {} minus commission {})", 
                adminPhoneLong, adminAmount, paymentAmount, userBonusAmount, commissionAmount);
        transfer.setPhone(adminPhoneLong);
        String payerName = user != null ? user.getFullNames() : "Guest User";
        transfer.setMessage("Payment from " + payerName + " to " + receiver.getCompanyName());
        
        // Build transfers list - admin payment (minus bonus and commission), user bonus back to payer, and commission (if applicable)
        java.util.List<MoPayInitiateRequest.Transfer> transfers = new java.util.ArrayList<>();
        transfers.add(transfer);
        
        logger.info("Added admin transfer (to hardcoded phone) - Amount: {}, Phone: {}", adminAmount, adminPhoneLong);
        
        // Add transfer back to payer with user bonus (similar to /api/payments/pay)
        logger.info("Checking if user bonus should be added - User bonus amount: {}, Is greater than zero: {}", 
                userBonusAmount, userBonusAmount.compareTo(BigDecimal.ZERO) > 0);
        
        if (userBonusAmount.compareTo(BigDecimal.ZERO) > 0) {
            MoPayInitiateRequest.Transfer bonusTransfer = new MoPayInitiateRequest.Transfer();
            bonusTransfer.setAmount(userBonusAmount);
            bonusTransfer.setPhone(payerPhoneLong);
            bonusTransfer.setMessage("User bonus for payment to " + receiver.getCompanyName());
            transfers.add(bonusTransfer);
            logger.info("✅ Added user bonus transfer back to payer - Amount: {}, Phone: {}", userBonusAmount, payerPhoneLong);
        } else {
            logger.warn("⚠️ User bonus amount is zero or negative, NOT adding bonus transfer. Amount: {}", userBonusAmount);
        }
        
        // Add transfer to commission phone number with commission amount (if commission is configured)
        logger.info("Checking if commission should be added - Commission amount: {}, Commission phone: {}, Is greater than zero: {}", 
                commissionAmount, commissionPhoneNumber, commissionAmount.compareTo(BigDecimal.ZERO) > 0);
        
        if (commissionAmount.compareTo(BigDecimal.ZERO) > 0 && commissionPhoneNumber != null && !commissionPhoneNumber.trim().isEmpty()) {
            // Normalize commission phone to 12 digits
            String normalizedCommissionPhone = normalizePhoneTo12Digits(commissionPhoneNumber);
            
            // Validate the normalized phone is exactly 12 digits
            if (normalizedCommissionPhone.length() != 12) {
                throw new RuntimeException("Commission phone number must be normalized to exactly 12 digits. Original: '" + commissionPhoneNumber + "', Normalized: '" + normalizedCommissionPhone + "' (" + normalizedCommissionPhone.length() + " digits)");
            }
            
            // Validate it's numeric only
            if (!normalizedCommissionPhone.matches("^[0-9]{12}$")) {
                throw new RuntimeException("Normalized commission phone must contain only digits. Got: '" + normalizedCommissionPhone + "'");
            }
            
            Long commissionPhoneLong = Long.parseLong(normalizedCommissionPhone);
            
            MoPayInitiateRequest.Transfer commissionTransfer = new MoPayInitiateRequest.Transfer();
            commissionTransfer.setAmount(commissionAmount);
            commissionTransfer.setPhone(commissionPhoneLong);
            commissionTransfer.setMessage("Commission for payment to " + receiver.getCompanyName());
            transfers.add(commissionTransfer);
            logger.info("✅ Added commission transfer to commissioner - Amount: {}, Phone: {}", commissionAmount, commissionPhoneLong);
        } else {
            logger.info("⚠️ Commission amount is zero or commission phone not configured, NOT adding commission transfer.");
        }
        
        logger.info("=== TRANSFERS SUMMARY ===");
        logger.info("Total transfers: {}", transfers.size());
        BigDecimal totalTransferAmount = BigDecimal.ZERO;
        for (int i = 0; i < transfers.size(); i++) {
            MoPayInitiateRequest.Transfer t = transfers.get(i);
            logger.info("Transfer {}: Amount={}, Phone={}, Message={}", i + 1, t.getAmount(), t.getPhone(), t.getMessage());
            totalTransferAmount = totalTransferAmount.add(t.getAmount());
        }
        logger.info("Total transfer amount: {}, Payment amount: {}", totalTransferAmount, paymentAmount);
        logger.info("Main MoPay request amount: {}", moPayRequest.getAmount());
        
        moPayRequest.setTransfers(transfers);

        // Initiate payment with MoPay
        MoPayResponse moPayResponse = moPayService.initiatePayment(moPayRequest);

        // Create transaction record - MOMO PAYMENT starts as PENDING
        // Note: If userId is null, user will be null - this requires user_id to be nullable in Transaction entity
        Transaction transaction = new Transaction();
        transaction.setUser(user); // Can be null for guest payments
        transaction.setPaymentCategory(paymentCategory);
        transaction.setReceiver(receiver);
        transaction.setTransactionType(TransactionType.PAYMENT);
        transaction.setAmount(paymentAmount);
        transaction.setPhoneNumber(payerPhone); // Payer's phone
        transaction.setMessage(request.getMessage() != null ? request.getMessage() : "MOMO payment to " + receiver.getCompanyName());
        // For MOMO payments, user balance is not deducted (they pay from MOMO wallet)
        // Balance will be set after MoPay confirms the payment
        if (user != null) {
            transaction.setBalanceBefore(user.getAmountRemaining()); // User's card balance before (for reference)
        }
        transaction.setDiscountAmount(discountAmount);
        transaction.setUserBonusAmount(userBonusAmount);
        transaction.setAdminIncomeAmount(adminIncomeAmount);
        transaction.setReceiverBalanceBefore(receiverBalanceBefore); // Balance owner's balance before
        transaction.setReceiverBalanceAfter(receiverBalanceAfter); // Balance owner's balance after (to be applied on SUCCESS)

        // Check MoPay response - API returns status 201 and transactionId on success
        String transactionId = moPayResponse != null ? moPayResponse.getTransactionId() : null;
        if (moPayResponse != null && moPayResponse.getStatus() != null && moPayResponse.getStatus() == 201 
            && transactionId != null) {
            // Successfully initiated - store transaction ID and set as PENDING
            transaction.setMopayTransactionId(transactionId);
            transaction.setStatus(TransactionStatus.PENDING);
            
            // Note: SMS and WhatsApp notifications will be sent only when payment is confirmed (SUCCESS)
            // See checkTransactionStatus method for notification logic
        } else {
            // Initiation failed - mark as FAILED with error message
            transaction.setStatus(TransactionStatus.FAILED);
            String errorMessage = moPayResponse != null && moPayResponse.getMessage() != null 
                ? moPayResponse.getMessage() 
                : "Payment initiation failed - status: " + (moPayResponse != null ? moPayResponse.getStatus() : "null") 
                    + ", transactionId: " + transactionId;
            transaction.setMessage(errorMessage);
        }

        // DO NOT update balances here - balances will be updated when status is checked and becomes SUCCESS
        Transaction savedTransaction = transactionRepository.save(transaction);
        return mapToPaymentResponse(savedTransaction);
    }

    @Transactional(readOnly = true)
    public BalanceResponse checkBalance(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Calculate total bonus received from all successful payment transactions
        BigDecimal totalBonusReceived = transactionRepository.sumUserBonusByUserId(userId);
        if (totalBonusReceived == null) {
            totalBonusReceived = BigDecimal.ZERO;
        }
        
        // Note: amountRemaining already includes bonuses that were added back during payment processing
        // amountRemainingWithBonus shows the effective balance (which already has bonuses included)
        // We display totalBonusReceived separately for tracking purposes
        BigDecimal amountRemainingWithBonus = user.getAmountRemaining();

        BalanceResponse response = new BalanceResponse();
        response.setUserId(user.getId());
        response.setFullNames(user.getFullNames());
        response.setPhoneNumber(user.getPhoneNumber());
        response.setEmail(user.getEmail());
        response.setIsAssignedNfcCard(user.getIsAssignedNfcCard());
        response.setNfcCardId(user.getNfcCardId());
        response.setAmountOnCard(user.getAmountOnCard());
        response.setAmountRemaining(user.getAmountRemaining());
        response.setTotalBonusReceived(totalBonusReceived);
        response.setAmountRemainingWithBonus(amountRemainingWithBonus);
        response.setStatus(user.getStatus());
        response.setLastTransactionDate(user.getLastTransactionDate());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }

    public PaymentResponse checkTransactionStatus(String mopayTransactionId) {
        Transaction transaction = transactionRepository.findByMopayTransactionIdWithUser(mopayTransactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        // Check status with MoPay
        MoPayResponse moPayResponse = moPayService.checkTransactionStatus(mopayTransactionId);

        if (moPayResponse != null) {
            // Check-status endpoint might return status in different formats
            // Try to determine if transaction is successful
            String mopayStatus = null;
            if (moPayResponse.getStatus() != null) {
                // If status is a string field, use it directly
                mopayStatus = moPayResponse.getStatus().toString();
            } else if (moPayResponse.getTransaction_id() != null) {
                // Sometimes status might be in transaction_id field
                mopayStatus = moPayResponse.getTransaction_id();
            }
            
            // Update transaction status based on MoPay response
            if ("SUCCESS".equalsIgnoreCase(mopayStatus) || "200".equals(mopayStatus) 
                || (moPayResponse.getSuccess() != null && moPayResponse.getSuccess())) {
                // Track if this is a status transition (from PENDING to SUCCESS)
                boolean isStatusTransition = transaction.getStatus() == TransactionStatus.PENDING;
                
                // Only update balance if transitioning from PENDING to SUCCESS
                if (isStatusTransition) {
                    User user = transaction.getUser();
                    
                    if (transaction.getTransactionType() == TransactionType.TOP_UP) {
                        // Add to user balance for top-ups
                        BigDecimal newBalance = user.getAmountRemaining().add(transaction.getAmount());
                        user.setAmountRemaining(newBalance);
                        user.setAmountOnCard(user.getAmountOnCard().add(transaction.getAmount()));
                        transaction.setBalanceAfter(newBalance);
                    } else if (transaction.getTransactionType() == TransactionType.PAYMENT && transaction.getMopayTransactionId() != null) {
                        // This is a MOMO PAYMENT - update receiver balance and user bonus
                        Receiver receiver = transaction.getReceiver();
                        if (receiver != null) {
                            // Determine the balance owner: if receiver is a submerchant, use parent's balance
                            Receiver balanceOwner = receiver.getParentReceiver() != null ? receiver.getParentReceiver() : receiver;
                            
                            // Get the calculated values from transaction
                            BigDecimal paymentAmount = transaction.getAmount();
                            BigDecimal userBonusAmount = transaction.getUserBonusAmount() != null ? transaction.getUserBonusAmount() : BigDecimal.ZERO;
                            BigDecimal receiverBalanceAfter = transaction.getReceiverBalanceAfter();
                            
                            // Update balance owner's remaining balance (shared balance)
                            balanceOwner.setRemainingBalance(receiverBalanceAfter);
                            balanceOwner.setLastTransactionDate(LocalDateTime.now());
                            
                            // Update balance owner's wallet balance and total received (shared)
                            BigDecimal balanceOwnerNewWallet = balanceOwner.getWalletBalance().add(paymentAmount);
                            BigDecimal balanceOwnerNewTotal = balanceOwner.getTotalReceived().add(paymentAmount);
                            balanceOwner.setWalletBalance(balanceOwnerNewWallet);
                            balanceOwner.setTotalReceived(balanceOwnerNewTotal);
                            receiverRepository.save(balanceOwner);
                            
                            // Also update individual receiver's wallet balance and total received
                            if (receiver.getId() != balanceOwner.getId()) {
                                // Only update if receiver is different from balance owner
                                BigDecimal receiverNewBalance = receiver.getWalletBalance().add(paymentAmount);
                                BigDecimal receiverNewTotal = receiver.getTotalReceived().add(paymentAmount);
                                receiver.setWalletBalance(receiverNewBalance);
                                receiver.setTotalReceived(receiverNewTotal);
                                receiver.setLastTransactionDate(LocalDateTime.now());
                                receiverRepository.save(receiver);
                            }
                            
                            // Add user bonus to user's card balance if applicable (only if user exists)
                            User transactionUser = transaction.getUser();
                            if (transactionUser != null) {
                                if (userBonusAmount.compareTo(BigDecimal.ZERO) > 0) {
                                    BigDecimal userBalanceBefore = transactionUser.getAmountRemaining();
                                    BigDecimal userNewBalance = userBalanceBefore.add(userBonusAmount);
                                    transactionUser.setAmountRemaining(userNewBalance);
                                    transaction.setBalanceAfter(userNewBalance);
                                } else {
                                    // No bonus, so balance remains the same
                                    transaction.setBalanceAfter(transactionUser.getAmountRemaining());
                                }
                                
                                transactionUser.setLastTransactionDate(LocalDateTime.now());
                                userRepository.save(transactionUser);
                            }
                            // If user is null (guest payment), user bonus is not credited
                        }
                    }
                }
                
                transaction.setStatus(TransactionStatus.SUCCESS);
                
                // Send WhatsApp notifications for all PAYMENT transactions when status transitions to SUCCESS
                // Only send on status transition (PENDING -> SUCCESS) to avoid duplicate notifications
                if (isStatusTransition && transaction.getTransactionType() == TransactionType.PAYMENT) {
                    Receiver receiver = transaction.getReceiver();
                    if (receiver != null) {
                        try {
                            // Determine payment method and payer information
                            boolean isMomoPayment = transaction.getMopayTransactionId() != null;
                            String paymentMethod = isMomoPayment ? "MM" : "Card";
                            
                            // Get payer phone and name
                            String payerPhone = null;
                            User transactionUser = transaction.getUser();
                            String payerName = "Guest User"; // Default
                            
                            if (isMomoPayment) {
                                // For MOMO payments, phoneNumber is the payer's phone
                                payerPhone = transaction.getPhoneNumber();
                                if (transactionUser != null && transactionUser.getFullNames() != null 
                                    && !transactionUser.getFullNames().trim().isEmpty()) {
                                    payerName = transactionUser.getFullNames();
                                } else if (payerPhone != null) {
                                    payerName = payerPhone;
                                }
                            } else {
                                // For NFC Card payments, user is always present
                                if (transactionUser != null) {
                                    payerPhone = transactionUser.getPhoneNumber();
                                    if (transactionUser.getFullNames() != null 
                                        && !transactionUser.getFullNames().trim().isEmpty()) {
                                        payerName = transactionUser.getFullNames();
                                    } else if (payerPhone != null) {
                                        payerName = payerPhone;
                                    }
                                }
                            }
                            
                            BigDecimal paymentAmount = transaction.getAmount();
                            BigDecimal userBonusAmount = transaction.getUserBonusAmount() != null ? transaction.getUserBonusAmount() : BigDecimal.ZERO;
                            
                            // Send WhatsApp to receiver
                            if (receiver.getReceiverPhone() != null) {
                                String receiverPhoneNormalized = normalizePhoneTo12Digits(receiver.getReceiverPhone());
                                String receiverWhatsAppMessage = String.format("[%s]: Paid %s RWF to [%s] via %s.",
                                    payerName, paymentAmount.toPlainString(), receiver.getCompanyName(), paymentMethod);
                                whatsAppService.sendWhatsApp(receiverWhatsAppMessage, receiverPhoneNormalized);
                                logger.info("WhatsApp sent to receiver {} about payment (status: SUCCESS, method: {}): {}", 
                                    receiverPhoneNormalized, paymentMethod, receiverWhatsAppMessage);
                            }
                            
                            // Send WhatsApp to payer (user) if phone is available
                            if (payerPhone != null && !payerPhone.trim().isEmpty()) {
                                String payerPhoneNormalized = normalizePhoneTo12Digits(payerPhone);
                                String userWhatsAppMessage;
                                
                                // Include bonus amount in message if applicable
                                if (userBonusAmount.compareTo(BigDecimal.ZERO) > 0) {
                                    userWhatsAppMessage = String.format("[%s]: Paid %s RWF to [%s] via %s. Bonus: %s RWF returned.",
                                        payerName, paymentAmount.toPlainString(), receiver.getCompanyName(), paymentMethod, userBonusAmount.toPlainString());
                                } else {
                                    userWhatsAppMessage = String.format("[%s]: Paid %s RWF to [%s] via %s.",
                                        payerName, paymentAmount.toPlainString(), receiver.getCompanyName(), paymentMethod);
                                }
                                
                                whatsAppService.sendWhatsApp(userWhatsAppMessage, payerPhoneNormalized);
                                logger.info("WhatsApp sent to payer {} about payment (status: SUCCESS, method: {}): {}", 
                                    payerPhoneNormalized, paymentMethod, userWhatsAppMessage);
                            } else {
                                logger.warn("Payer phone number is null or empty in transaction, skipping WhatsApp to payer");
                            }
                        } catch (Exception e) {
                            logger.error("Failed to send WhatsApp notifications for payment status check: ", e);
                            // Don't fail the transaction if WhatsApp fails
                        }
                    }
                }
            } else if ("FAILED".equalsIgnoreCase(mopayStatus) || "400".equals(mopayStatus) 
                || "500".equals(mopayStatus) || (moPayResponse.getSuccess() != null && !moPayResponse.getSuccess())) {
                transaction.setStatus(TransactionStatus.FAILED);
                if (moPayResponse.getMessage() != null) {
                    transaction.setMessage(moPayResponse.getMessage());
                }
            }
            transactionRepository.save(transaction);
        }

        return mapToPaymentResponse(transaction);
    }

    @Transactional(readOnly = true)
    public PaginatedResponse<PaymentResponse> getAllTransactions(int page, int size, LocalDateTime fromDate, LocalDateTime toDate) {
        // Build dynamic query using EntityManager
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT t FROM Transaction t ");
        queryBuilder.append("LEFT JOIN FETCH t.user u ");
        queryBuilder.append("LEFT JOIN FETCH t.paymentCategory pc ");
        queryBuilder.append("LEFT JOIN FETCH t.receiver r ");
        queryBuilder.append("WHERE 1=1 ");
        
        // Add date range filters
        if (fromDate != null) {
            queryBuilder.append("AND t.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            queryBuilder.append("AND t.createdAt <= :toDate ");
        }
        
        queryBuilder.append("ORDER BY t.createdAt DESC");
        
        // Create query
        Query query = entityManager.createQuery(queryBuilder.toString(), Transaction.class);
        
        // Set date parameters if provided
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        
        // Get total count (for pagination)
        StringBuilder countQueryBuilder = new StringBuilder();
        countQueryBuilder.append("SELECT COUNT(t) FROM Transaction t ");
        countQueryBuilder.append("WHERE 1=1 ");
        
        if (fromDate != null) {
            countQueryBuilder.append("AND t.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            countQueryBuilder.append("AND t.createdAt <= :toDate ");
        }
        
        Query countQuery = entityManager.createQuery(countQueryBuilder.toString(), Long.class);
        
        if (fromDate != null) {
            countQuery.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            countQuery.setParameter("toDate", toDate);
        }
        
        long totalElements = (Long) countQuery.getSingleResult();
        
        // Apply pagination
        int offset = page * size;
        query.setFirstResult(offset);
        query.setMaxResults(size);
        
        @SuppressWarnings("unchecked")
        List<Transaction> transactions = (List<Transaction>) query.getResultList();
        
        // Convert to PaymentResponse
        List<PaymentResponse> content = transactions.stream()
                .map(this::mapToPaymentResponse)
                .collect(Collectors.toList());
        
        // Calculate pagination metadata
        int totalPages = (int) Math.ceil((double) totalElements / size);
        
        PaginatedResponse<PaymentResponse> response = new PaginatedResponse<>();
        response.setContent(content);
        response.setTotalElements(totalElements);
        response.setTotalPages(totalPages);
        response.setCurrentPage(page);
        response.setPageSize(size);
        response.setFirst(page == 0);
        response.setLast(page >= totalPages - 1);
        
        return response;
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getTransactionsByUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return transactionRepository.findByUserOrderByCreatedAtDescWithUser(user)
                .stream()
                .map(this::mapToPaymentResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PaymentResponse getTransactionById(UUID transactionId) {
        Transaction transaction = transactionRepository.findByIdWithUser(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        return mapToPaymentResponse(transaction);
    }

    @Transactional(readOnly = true)
    public ReceiverTransactionsResponse getTransactionsByReceiver(UUID receiverId, int page, int size, LocalDateTime fromDate, LocalDateTime toDate) {
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found"));
        
        // Build dynamic query using EntityManager
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT t FROM Transaction t ");
        queryBuilder.append("LEFT JOIN FETCH t.user u ");
        queryBuilder.append("LEFT JOIN FETCH t.paymentCategory pc ");
        queryBuilder.append("LEFT JOIN FETCH t.receiver r ");
        queryBuilder.append("WHERE t.receiver.id = :receiverId ");
        
        // Add date range filters
        if (fromDate != null) {
            queryBuilder.append("AND t.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            queryBuilder.append("AND t.createdAt <= :toDate ");
        }
        
        queryBuilder.append("ORDER BY t.createdAt DESC");
        
        // Create query
        Query query = entityManager.createQuery(queryBuilder.toString(), Transaction.class);
        query.setParameter("receiverId", receiverId);
        
        // Set date parameters if provided
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        
        // Get total count (for pagination)
        StringBuilder countQueryBuilder = new StringBuilder();
        countQueryBuilder.append("SELECT COUNT(t) FROM Transaction t ");
        countQueryBuilder.append("WHERE t.receiver.id = :receiverId ");
        
        if (fromDate != null) {
            countQueryBuilder.append("AND t.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            countQueryBuilder.append("AND t.createdAt <= :toDate ");
        }
        
        Query countQuery = entityManager.createQuery(countQueryBuilder.toString(), Long.class);
        countQuery.setParameter("receiverId", receiverId);
        
        if (fromDate != null) {
            countQuery.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            countQuery.setParameter("toDate", toDate);
        }
        
        // Apply pagination
        int offset = page * size;
        query.setFirstResult(offset);
        query.setMaxResults(size);
        
        @SuppressWarnings("unchecked")
        List<Transaction> paginatedTransactions = (List<Transaction>) query.getResultList();
        
        // Get ALL transactions for statistics calculation (without pagination)
        StringBuilder statsQueryBuilder = new StringBuilder();
        statsQueryBuilder.append("SELECT t FROM Transaction t ");
        statsQueryBuilder.append("LEFT JOIN FETCH t.user u ");
        statsQueryBuilder.append("WHERE t.receiver.id = :receiverId ");
        
        if (fromDate != null) {
            statsQueryBuilder.append("AND t.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            statsQueryBuilder.append("AND t.createdAt <= :toDate ");
        }
        
        Query statsQuery = entityManager.createQuery(statsQueryBuilder.toString(), Transaction.class);
        statsQuery.setParameter("receiverId", receiverId);
        
        if (fromDate != null) {
            statsQuery.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            statsQuery.setParameter("toDate", toDate);
        }
        
        @SuppressWarnings("unchecked")
        List<Transaction> allTransactions = (List<Transaction>) statsQuery.getResultList();
        
        // Convert paginated transactions to PaymentResponse
        List<PaymentResponse> transactionResponses = paginatedTransactions.stream()
                .map(this::mapToPaymentResponse)
                .collect(Collectors.toList());
        
        // Calculate statistics from ALL transactions (not just paginated)
        ReceiverTransactionsResponse.TransactionStatistics statistics = calculateReceiverTransactionStatistics(allTransactions);
        
        ReceiverTransactionsResponse response = new ReceiverTransactionsResponse();
        response.setTransactions(transactionResponses);
        response.setStatistics(statistics);
        // Add pagination info to response (we'll need to update ReceiverTransactionsResponse to include pagination)
        // For now, statistics will reflect all transactions in the date range
        
        return response;
    }
    
    private ReceiverTransactionsResponse.TransactionStatistics calculateReceiverTransactionStatistics(List<Transaction> transactions) {
        ReceiverTransactionsResponse.TransactionStatistics stats = new ReceiverTransactionsResponse.TransactionStatistics();
        
        long totalTransactions = transactions.size();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalDiscountAmount = BigDecimal.ZERO;
        BigDecimal totalUserBonusAmount = BigDecimal.ZERO;
        BigDecimal totalAdminIncomeAmount = BigDecimal.ZERO;
        long successfulTransactions = 0;
        long pendingTransactions = 0;
        long failedTransactions = 0;
        java.util.Set<UUID> distinctUsers = new java.util.HashSet<>();
        
        for (Transaction transaction : transactions) {
            if (transaction.getTransactionType() == TransactionType.PAYMENT) {
                // Count distinct users
                if (transaction.getUser() != null) {
                    distinctUsers.add(transaction.getUser().getId());
                }
                
                // Sum amounts for successful transactions
                if (transaction.getStatus() == TransactionStatus.SUCCESS) {
                    if (transaction.getAmount() != null) {
                        totalRevenue = totalRevenue.add(transaction.getAmount());
                    }
                    if (transaction.getDiscountAmount() != null) {
                        totalDiscountAmount = totalDiscountAmount.add(transaction.getDiscountAmount());
                    }
                    if (transaction.getUserBonusAmount() != null) {
                        totalUserBonusAmount = totalUserBonusAmount.add(transaction.getUserBonusAmount());
                    }
                    if (transaction.getAdminIncomeAmount() != null) {
                        totalAdminIncomeAmount = totalAdminIncomeAmount.add(transaction.getAdminIncomeAmount());
                    }
                    successfulTransactions++;
                } else if (transaction.getStatus() == TransactionStatus.PENDING) {
                    pendingTransactions++;
                } else {
                    failedTransactions++;
                }
            }
        }
        
        stats.setTotalTransactions(totalTransactions);
        stats.setTotalRevenue(totalRevenue);
        stats.setTotalCustomers((long) distinctUsers.size());
        stats.setTotalDiscountAmount(totalDiscountAmount);
        stats.setTotalUserBonusAmount(totalUserBonusAmount);
        stats.setTotalAdminIncomeAmount(totalAdminIncomeAmount);
        stats.setSuccessfulTransactions(successfulTransactions);
        stats.setPendingTransactions(pendingTransactions);
        stats.setFailedTransactions(failedTransactions);
        
        return stats;
    }

    @Transactional(readOnly = true)
    public PaginatedResponse<PaymentResponse> getAllTransactionsForMainMerchant(
            UUID mainReceiverId, 
            int page, 
            int size,
            String search,
            LocalDateTime fromDate,
            LocalDateTime toDate) {
        // Verify main receiver exists and is a main merchant (has no parent)
        Receiver mainReceiver = receiverRepository.findById(mainReceiverId)
                .orElseThrow(() -> new RuntimeException("Main receiver not found"));
        
        if (mainReceiver.getParentReceiver() != null) {
            throw new RuntimeException("Receiver is not a main merchant. Only main merchants can view all transactions.");
        }
        
        // Get all submerchants
        List<Receiver> submerchants = receiverRepository.findByParentReceiverId(mainReceiverId);
        
        // Build list of receiver IDs (main + all submerchants)
        List<UUID> receiverIds = new java.util.ArrayList<>();
        receiverIds.add(mainReceiverId);
        for (Receiver submerchant : submerchants) {
            receiverIds.add(submerchant.getId());
        }
        
        // Build dynamic query using EntityManager
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT t FROM Transaction t ");
        queryBuilder.append("LEFT JOIN FETCH t.user u ");
        queryBuilder.append("LEFT JOIN FETCH t.paymentCategory pc ");
        queryBuilder.append("LEFT JOIN FETCH t.receiver r ");
        queryBuilder.append("WHERE t.receiver.id IN :receiverIds ");
        queryBuilder.append("AND t.transactionType = 'PAYMENT' ");
        
        // Add search filter
        if (search != null && !search.trim().isEmpty()) {
            queryBuilder.append("AND (LOWER(u.fullNames) LIKE LOWER(:search) ");
            queryBuilder.append("OR LOWER(u.phoneNumber) LIKE LOWER(:search) ");
            queryBuilder.append("OR LOWER(r.companyName) LIKE LOWER(:search) ");
            queryBuilder.append("OR LOWER(pc.name) LIKE LOWER(:search)) ");
        }
        
        // Add date range filters
        if (fromDate != null) {
            queryBuilder.append("AND t.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            queryBuilder.append("AND t.createdAt <= :toDate ");
        }
        
        queryBuilder.append("ORDER BY t.createdAt DESC");
        
        // Create query
        Query query = entityManager.createQuery(queryBuilder.toString(), Transaction.class);
        query.setParameter("receiverIds", receiverIds);
        
        // Set search parameter if provided
        if (search != null && !search.trim().isEmpty()) {
            String searchPattern = "%" + search.trim() + "%";
            query.setParameter("search", searchPattern);
        }
        
        // Set date parameters if provided
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        
        // Get total count (for pagination)
        StringBuilder countQueryBuilder = new StringBuilder();
        countQueryBuilder.append("SELECT COUNT(t) FROM Transaction t ");
        countQueryBuilder.append("LEFT JOIN t.user u ");
        countQueryBuilder.append("LEFT JOIN t.paymentCategory pc ");
        countQueryBuilder.append("LEFT JOIN t.receiver r ");
        countQueryBuilder.append("WHERE t.receiver.id IN :receiverIds ");
        countQueryBuilder.append("AND t.transactionType = 'PAYMENT' ");
        
        if (search != null && !search.trim().isEmpty()) {
            countQueryBuilder.append("AND (LOWER(u.fullNames) LIKE LOWER(:search) ");
            countQueryBuilder.append("OR LOWER(u.phoneNumber) LIKE LOWER(:search) ");
            countQueryBuilder.append("OR LOWER(r.companyName) LIKE LOWER(:search) ");
            countQueryBuilder.append("OR LOWER(pc.name) LIKE LOWER(:search)) ");
        }
        
        if (fromDate != null) {
            countQueryBuilder.append("AND t.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            countQueryBuilder.append("AND t.createdAt <= :toDate ");
        }
        
        Query countQuery = entityManager.createQuery(countQueryBuilder.toString(), Long.class);
        countQuery.setParameter("receiverIds", receiverIds);
        
        if (search != null && !search.trim().isEmpty()) {
            String searchPattern = "%" + search.trim() + "%";
            countQuery.setParameter("search", searchPattern);
        }
        
        if (fromDate != null) {
            countQuery.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            countQuery.setParameter("toDate", toDate);
        }
        
        long totalElements = (Long) countQuery.getSingleResult();
        
        // Apply pagination
        int offset = page * size;
        query.setFirstResult(offset);
        query.setMaxResults(size);
        
        @SuppressWarnings("unchecked")
        List<Transaction> transactions = (List<Transaction>) query.getResultList();
        
        // Convert to PaymentResponse (include receiver information to identify submerchants)
        List<PaymentResponse> content = transactions.stream()
                .map(t -> mapToPaymentResponse(t, mainReceiverId))
                .collect(Collectors.toList());
        
        // Calculate pagination metadata
        int totalPages = (int) Math.ceil((double) totalElements / size);
        
        PaginatedResponse<PaymentResponse> response = new PaginatedResponse<>();
        response.setContent(content);
        response.setTotalElements(totalElements);
        response.setTotalPages(totalPages);
        response.setCurrentPage(page);
        response.setPageSize(size);
        response.setFirst(page == 0);
        response.setLast(page >= totalPages - 1);
        
        return response;
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getTransactionsForSubmerchant(UUID mainReceiverId, UUID submerchantId) {
        // Verify main receiver exists
        if (!receiverRepository.existsById(mainReceiverId)) {
            throw new RuntimeException("Main receiver not found");
        }
        
        // Verify submerchant exists and belongs to main receiver
        Receiver submerchant = receiverRepository.findById(submerchantId)
                .orElseThrow(() -> new RuntimeException("Submerchant receiver not found"));
        
        if (submerchant.getParentReceiver() == null || !submerchant.getParentReceiver().getId().equals(mainReceiverId)) {
            throw new RuntimeException("Receiver is not a submerchant of the specified main merchant.");
        }
        
        // Return submerchant's transactions
        return transactionRepository.findByReceiverOrderByCreatedAtDescWithUser(submerchant)
                .stream()
                .map(this::mapToPaymentResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<UserBonusHistoryResponse> getUserBonusHistory(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        // Get all successful payment transactions with bonuses
        return transactionRepository.findByUserOrderByCreatedAtDescWithUser(user)
                .stream()
                .filter(t -> t.getTransactionType() == TransactionType.PAYMENT 
                        && t.getStatus() == TransactionStatus.SUCCESS
                        && t.getUserBonusAmount() != null 
                        && t.getUserBonusAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(this::mapToUserBonusHistoryResponse)
                .collect(Collectors.toList());
    }

    private UserBonusHistoryResponse mapToUserBonusHistoryResponse(Transaction transaction) {
        UserBonusHistoryResponse response = new UserBonusHistoryResponse();
        response.setTransactionId(transaction.getId());
        
        // Handle null user for guest payments
        if (transaction.getUser() != null) {
            response.setUserId(transaction.getUser().getId());
            response.setUserFullNames(transaction.getUser().getFullNames());
        } else {
            response.setUserId(null);
            response.setUserFullNames("Guest User");
        }
        
        if (transaction.getReceiver() != null) {
            response.setReceiverId(transaction.getReceiver().getId());
            response.setReceiverCompanyName(transaction.getReceiver().getCompanyName());
        }
        
        if (transaction.getPaymentCategory() != null) {
            response.setPaymentCategoryId(transaction.getPaymentCategory().getId());
            response.setPaymentCategoryName(transaction.getPaymentCategory().getName());
        }
        
        response.setPaymentAmount(transaction.getAmount());
        response.setBonusAmount(transaction.getUserBonusAmount());
        response.setTransactionDate(transaction.getCreatedAt());
        return response;
    }

    private PaymentResponse mapToPaymentResponse(Transaction transaction) {
        return mapToPaymentResponse(transaction, null);
    }
    
    private PaymentResponse mapToPaymentResponse(Transaction transaction, UUID mainReceiverId) {
        PaymentResponse response = new PaymentResponse();
        response.setId(transaction.getId());
        // Handle null user for guest MOMO payments
        if (transaction.getUser() != null) {
            response.setUserId(transaction.getUser().getId());
            response.setUser(mapToUserResponse(transaction.getUser())); // Include full user information
        }
        // Include payment category if it exists (for PAYMENT transactions)
        if (transaction.getPaymentCategory() != null) {
            response.setPaymentCategory(mapToPaymentCategoryResponse(transaction.getPaymentCategory()));
        }
        response.setTransactionType(transaction.getTransactionType());
        response.setAmount(transaction.getAmount());
        response.setMopayTransactionId(transaction.getMopayTransactionId());
        response.setStatus(transaction.getStatus());
        response.setBalanceBefore(transaction.getBalanceBefore());
        response.setBalanceAfter(transaction.getBalanceAfter());
        response.setDiscountAmount(transaction.getDiscountAmount());
        response.setUserBonusAmount(transaction.getUserBonusAmount());
        response.setAdminIncomeAmount(transaction.getAdminIncomeAmount());
        response.setReceiverBalanceBefore(transaction.getReceiverBalanceBefore());
        response.setReceiverBalanceAfter(transaction.getReceiverBalanceAfter());
        response.setCreatedAt(transaction.getCreatedAt());
        
        // Add receiver information (if mainReceiverId is provided, determine if it's a submerchant)
        if (transaction.getReceiver() != null) {
            response.setReceiverId(transaction.getReceiver().getId());
            response.setReceiverCompanyName(transaction.getReceiver().getCompanyName());
            // Check if this transaction was made by a submerchant (not the main receiver)
            if (mainReceiverId != null) {
                response.setIsSubmerchant(!transaction.getReceiver().getId().equals(mainReceiverId));
            }
            
            // Get commission settings for this receiver
            List<PaymentCommissionSetting> commissionSettings = paymentCommissionSettingRepository.findByReceiverIdAndIsActiveTrue(transaction.getReceiver().getId());
            List<com.pocketmoney.pocketmoney.dto.CommissionInfo> commissionInfoList = commissionSettings.stream()
                    .map(setting -> new com.pocketmoney.pocketmoney.dto.CommissionInfo(
                            setting.getPhoneNumber(),
                            setting.getCommissionPercentage()
                    ))
                    .collect(Collectors.toList());
            response.setCommissionSettings(commissionInfoList);
        }
        
        // Add payment method information
        // MOMO payments have mopayTransactionId (go through MoPay API) and phoneNumber is the payer's phone
        // NFC payments don't have mopayTransactionId (internal transfers) and phoneNumber is the receiver's phone
        if (transaction.getTransactionType() == TransactionType.PAYMENT) {
            if (transaction.getMopayTransactionId() != null && !transaction.getMopayTransactionId().trim().isEmpty()) {
                // MOMO payment - has mopayTransactionId, phoneNumber is payer's phone
                response.setPayerPhone(transaction.getPhoneNumber());
                response.setPaymentMethod("MOMO");
            } else {
                // NFC Card payment - no mopayTransactionId, internal transfer
                response.setPaymentMethod("NFC_CARD");
                // For NFC, phoneNumber field contains receiver phone (not payer), so we don't set payerPhone
            }
        } else if (transaction.getTransactionType() == TransactionType.TOP_UP) {
            // Top-up - typically MOMO, phoneNumber is the top-up source phone
            if (transaction.getPhoneNumber() != null && !transaction.getPhoneNumber().trim().isEmpty()) {
                response.setPayerPhone(transaction.getPhoneNumber());
            }
            response.setPaymentMethod("TOP_UP");
        } else {
            // REFUND or other
            response.setPaymentMethod("UNKNOWN");
        }
        
        return response;
    }

    private PaymentCategoryResponse mapToPaymentCategoryResponse(PaymentCategory paymentCategory) {
        PaymentCategoryResponse response = new PaymentCategoryResponse();
        response.setId(paymentCategory.getId());
        response.setName(paymentCategory.getName());
        response.setDescription(paymentCategory.getDescription());
        response.setIsActive(paymentCategory.getIsActive());
        response.setCreatedAt(paymentCategory.getCreatedAt());
        response.setUpdatedAt(paymentCategory.getUpdatedAt());
        return response;
    }

    private UserResponse mapToUserResponse(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setFullNames(user.getFullNames());
        response.setPhoneNumber(user.getPhoneNumber());
        response.setEmail(user.getEmail());
        response.setIsAssignedNfcCard(user.getIsAssignedNfcCard());
        response.setNfcCardId(user.getNfcCardId());
        response.setAmountOnCard(user.getAmountOnCard());
        response.setAmountRemaining(user.getAmountRemaining());
        response.setStatus(user.getStatus());
        response.setLastTransactionDate(user.getLastTransactionDate());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }

    @Transactional(readOnly = true)
    public AdminIncomeResponse getAdminIncome(LocalDateTime fromDate, LocalDateTime toDate, UUID receiverId, int page, int size) {
        // Set time to start/end of day if only date is provided
        if (fromDate != null && fromDate.getHour() == 0 && fromDate.getMinute() == 0 && fromDate.getSecond() == 0) {
            fromDate = fromDate.withHour(0).withMinute(0).withSecond(0);
        }
        if (toDate != null) {
            toDate = toDate.withHour(23).withMinute(59).withSecond(59);
        }

        // Use EntityManager to build dynamic queries and avoid PostgreSQL null parameter type issues
        BigDecimal totalIncome;
        Long totalTransactions;
        
        if (fromDate == null && toDate == null && receiverId == null) {
            // No filters - use simple repository methods
            totalIncome = transactionRepository.sumAdminIncomeAll();
            totalTransactions = transactionRepository.countAdminIncomeAll();
        } else {
            // Build dynamic query using EntityManager
            totalIncome = calculateAdminIncomeWithFilters(fromDate, toDate, receiverId);
            totalTransactions = countAdminIncomeWithFilters(fromDate, toDate, receiverId);
        }
        
        if (totalIncome == null) {
            totalIncome = BigDecimal.ZERO;
        }
        if (totalTransactions == null) {
            totalTransactions = 0L;
        }

        // Get breakdown by receiver if receiverId is not specified
        List<AdminIncomeResponse.IncomeBreakdown> breakdown = null;
        if (receiverId == null) {
            breakdown = getAdminIncomeBreakdown(fromDate, toDate);
        }

        // Get total assigned balance and breakdown
        BigDecimal totalAssignedBalance = calculateTotalAssignedBalance(fromDate, toDate, receiverId);
        List<AdminIncomeResponse.AssignedBalanceBreakdown> assignedBalanceBreakdown = null;
        if (receiverId == null) {
            assignedBalanceBreakdown = getAssignedBalanceBreakdown(fromDate, toDate);
        }

        // Get detailed transaction list with pagination
        List<AdminIncomeResponse.AdminIncomeTransaction> transactions = getAdminIncomeTransactionList(fromDate, toDate, receiverId, page, size);

        AdminIncomeResponse response = new AdminIncomeResponse();
        response.setTotalIncome(totalIncome);
        response.setTotalTransactions(totalTransactions);
        response.setTotalAssignedBalance(totalAssignedBalance);
        response.setFromDate(fromDate);
        response.setToDate(toDate);
        response.setBreakdown(breakdown);
        response.setAssignedBalanceBreakdown(assignedBalanceBreakdown);
        response.setTransactions(transactions);

        return response;
    }

    private BigDecimal calculateAdminIncomeWithFilters(LocalDateTime fromDate, LocalDateTime toDate, UUID receiverId) {
        StringBuilder sql = new StringBuilder(
            "SELECT COALESCE(SUM(t.admin_income_amount), 0) FROM transactions t " +
            "WHERE t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS' " +
            "AND t.admin_income_amount IS NOT NULL"
        );
        
        Query query = buildFilteredQuery(sql.toString(), fromDate, toDate, receiverId, entityManager);
        
        Object result = query.getSingleResult();
        return result != null ? (BigDecimal) result : BigDecimal.ZERO;
    }

    private Long countAdminIncomeWithFilters(LocalDateTime fromDate, LocalDateTime toDate, UUID receiverId) {
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FROM transactions t " +
            "WHERE t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS' " +
            "AND t.admin_income_amount IS NOT NULL"
        );
        
        Query query = buildFilteredQuery(sql.toString(), fromDate, toDate, receiverId, entityManager);
        
        Object result = query.getSingleResult();
        return result != null ? ((Number) result).longValue() : 0L;
    }

    private Query buildFilteredQuery(String baseSql, LocalDateTime fromDate, LocalDateTime toDate, UUID receiverId, EntityManager em) {
        StringBuilder sql = new StringBuilder(baseSql);
        
        if (fromDate != null) {
            sql.append(" AND t.created_at >= :fromDate");
        }
        if (toDate != null) {
            sql.append(" AND t.created_at <= :toDate");
        }
        if (receiverId != null) {
            sql.append(" AND t.receiver_id = :receiverId");
        }
        
        Query query = em.createNativeQuery(sql.toString());
        
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        if (receiverId != null) {
            query.setParameter("receiverId", receiverId);
        }
        
        return query;
    }

    private List<AdminIncomeResponse.IncomeBreakdown> getAdminIncomeBreakdown(LocalDateTime fromDate, LocalDateTime toDate) {
        StringBuilder sql = new StringBuilder(
            "SELECT r.id, r.company_name, COALESCE(SUM(t.admin_income_amount), 0), COUNT(t.id) " +
            "FROM transactions t " +
            "JOIN receivers r ON t.receiver_id = r.id " +
            "WHERE t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS' " +
            "AND t.admin_income_amount IS NOT NULL"
        );
        
        if (fromDate != null) {
            sql.append(" AND t.created_at >= :fromDate");
        }
        if (toDate != null) {
            sql.append(" AND t.created_at <= :toDate");
        }
        
        sql.append(" GROUP BY r.id, r.company_name ORDER BY SUM(t.admin_income_amount) DESC");
        
        Query query = entityManager.createNativeQuery(sql.toString());
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        
        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        
        return results.stream()
                .map(row -> {
                    AdminIncomeResponse.IncomeBreakdown item = new AdminIncomeResponse.IncomeBreakdown();
                    item.setReceiverId((UUID) row[0]);
                    item.setReceiverCompanyName((String) row[1]);
                    item.setIncome((BigDecimal) row[2]);
                    item.setTransactionCount(((Number) row[3]).longValue());
                    return item;
                })
                .collect(Collectors.toList());
    }

    private BigDecimal calculateTotalAssignedBalance(LocalDateTime fromDate, LocalDateTime toDate, UUID receiverId) {
        StringBuilder sql = new StringBuilder(
            "SELECT COALESCE(SUM(b.balance_difference), 0) FROM balance_assignment_history b " +
            "WHERE b.status = 'APPROVED' AND b.balance_difference IS NOT NULL"
        );
        
        if (fromDate != null) {
            sql.append(" AND b.approved_at >= :fromDate");
        }
        if (toDate != null) {
            sql.append(" AND b.approved_at <= :toDate");
        }
        if (receiverId != null) {
            sql.append(" AND b.receiver_id = :receiverId");
        }
        
        Query query = entityManager.createNativeQuery(sql.toString());
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        if (receiverId != null) {
            query.setParameter("receiverId", receiverId);
        }
        
        Object result = query.getSingleResult();
        return result != null ? (BigDecimal) result : BigDecimal.ZERO;
    }

    private List<AdminIncomeResponse.AssignedBalanceBreakdown> getAssignedBalanceBreakdown(LocalDateTime fromDate, LocalDateTime toDate) {
        StringBuilder sql = new StringBuilder(
            "SELECT r.id, r.company_name, COALESCE(SUM(b.balance_difference), 0), COUNT(b.id) " +
            "FROM balance_assignment_history b " +
            "JOIN receivers r ON b.receiver_id = r.id " +
            "WHERE b.status = 'APPROVED' AND b.balance_difference IS NOT NULL"
        );
        
        if (fromDate != null) {
            sql.append(" AND b.approved_at >= :fromDate");
        }
        if (toDate != null) {
            sql.append(" AND b.approved_at <= :toDate");
        }
        
        sql.append(" GROUP BY r.id, r.company_name ORDER BY SUM(b.balance_difference) DESC");
        
        Query query = entityManager.createNativeQuery(sql.toString());
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        
        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        
        return results.stream()
                .map(row -> {
                    AdminIncomeResponse.AssignedBalanceBreakdown item = new AdminIncomeResponse.AssignedBalanceBreakdown();
                    item.setReceiverId((UUID) row[0]);
                    item.setReceiverCompanyName((String) row[1]);
                    item.setAssignedBalance((BigDecimal) row[2]);
                    item.setAssignmentCount(((Number) row[3]).longValue());
                    return item;
                })
                .collect(Collectors.toList());
    }

    private List<AdminIncomeResponse.AdminIncomeTransaction> getAdminIncomeTransactionList(LocalDateTime fromDate, LocalDateTime toDate, UUID receiverId, int page, int size) {
        StringBuilder sql = new StringBuilder(
            "SELECT t.id, t.created_at, u.id, u.full_names, u.phone_number, " +
            "r.id, r.company_name, pc.id, pc.name, t.amount, " +
            "t.discount_amount, t.user_bonus_amount, t.admin_income_amount, t.status " +
            "FROM transactions t " +
            "JOIN users u ON t.user_id = u.id " +
            "JOIN receivers r ON t.receiver_id = r.id " +
            "LEFT JOIN payment_categories pc ON t.payment_category_id = pc.id " +
            "WHERE t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS' " +
            "AND t.admin_income_amount IS NOT NULL"
        );
        
        if (fromDate != null) {
            sql.append(" AND t.created_at >= :fromDate");
        }
        if (toDate != null) {
            sql.append(" AND t.created_at <= :toDate");
        }
        if (receiverId != null) {
            sql.append(" AND t.receiver_id = :receiverId");
        }
        
        sql.append(" ORDER BY t.created_at DESC");
        
        Query query = entityManager.createNativeQuery(sql.toString());
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        if (receiverId != null) {
            query.setParameter("receiverId", receiverId);
        }
        
        // Apply pagination
        int offset = page * size;
        query.setFirstResult(offset);
        query.setMaxResults(size);
        
        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        
        return results.stream()
                .map(row -> {
                    AdminIncomeResponse.AdminIncomeTransaction transaction = new AdminIncomeResponse.AdminIncomeTransaction();
                    transaction.setTransactionId((UUID) row[0]);
                    
                    // Handle timestamp conversion
                    if (row[1] instanceof java.sql.Timestamp) {
                        transaction.setTransactionDate(((java.sql.Timestamp) row[1]).toLocalDateTime());
                    } else if (row[1] instanceof LocalDateTime) {
                        transaction.setTransactionDate((LocalDateTime) row[1]);
                    }
                    
                    transaction.setUserId((UUID) row[2]);
                    transaction.setUserFullNames((String) row[3]);
                    transaction.setUserPhoneNumber((String) row[4]);
                    transaction.setReceiverId((UUID) row[5]);
                    transaction.setReceiverCompanyName((String) row[6]);
                    transaction.setPaymentCategoryId((UUID) row[7]);
                    transaction.setPaymentCategoryName((String) row[8]);
                    transaction.setPaymentAmount((BigDecimal) row[9]);
                    transaction.setDiscountAmount((BigDecimal) row[10]);
                    transaction.setUserBonusAmount((BigDecimal) row[11]);
                    transaction.setAdminIncomeAmount((BigDecimal) row[12]);
                    transaction.setStatus(row[13] != null ? row[13].toString() : null);
                    return transaction;
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AdminDashboardStatisticsResponse getAdminDashboardStatistics() {
        AdminDashboardStatisticsResponse response = new AdminDashboardStatisticsResponse();

        // Total Users
        long totalUsers = userRepository.count();
        response.setTotalUsers(totalUsers);

        // Total Merchants (Receivers) - count all receivers
        long totalMerchants = receiverRepository.count();
        response.setTotalMerchants(totalMerchants);

        // Total Transactions - count all successful payment transactions
        Long totalTransactions = transactionRepository.countAllSuccessfulPaymentTransactions();
        response.setTotalTransactions(totalTransactions != null ? totalTransactions : 0L);

        // Total Revenue - sum of all admin income
        BigDecimal totalRevenue = transactionRepository.sumAdminIncomeAll();
        response.setTotalRevenue(totalRevenue != null ? totalRevenue : BigDecimal.ZERO);

        // Recent Activities - get 5 most recent transactions
        List<AdminDashboardStatisticsResponse.RecentActivity> recentActivities = 
            transactionRepository.findAllWithUser().stream()
                .limit(5)
                .map(this::mapToRecentActivity)
                .collect(Collectors.toList());
        response.setRecentActivities(recentActivities);

        return response;
    }

    private AdminDashboardStatisticsResponse.RecentActivity mapToRecentActivity(Transaction transaction) {
        AdminDashboardStatisticsResponse.RecentActivity activity = 
            new AdminDashboardStatisticsResponse.RecentActivity();
        
        activity.setId(transaction.getId());
        activity.setType(transaction.getTransactionType().name());
        activity.setAmount(transaction.getAmount());
        activity.setCreatedAt(transaction.getCreatedAt());
        activity.setStatus(transaction.getStatus().name());

        // Handle null user for guest payments
        String userName = transaction.getUser() != null ? transaction.getUser().getFullNames() : "Guest User";
        
        // Set description based on transaction type
        if (transaction.getTransactionType() == TransactionType.PAYMENT) {
            activity.setDescription("Payment to " + 
                (transaction.getReceiver() != null ? transaction.getReceiver().getCompanyName() : "Unknown"));
            activity.setUserName(userName);
            if (transaction.getReceiver() != null) {
                activity.setReceiverName(transaction.getReceiver().getCompanyName());
            }
            if (transaction.getPaymentCategory() != null) {
                activity.setPaymentCategoryName(transaction.getPaymentCategory().getName());
            }
        } else if (transaction.getTransactionType() == TransactionType.TOP_UP) {
            activity.setDescription("Top-up for " + userName);
            activity.setUserName(userName);
        } else {
            activity.setDescription(transaction.getMessage() != null ? transaction.getMessage() : 
                transaction.getTransactionType().name());
            activity.setUserName(userName);
        }

        return activity;
    }
}


