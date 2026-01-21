package com.pocketmoney.pocketmoney.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pocketmoney.pocketmoney.dto.AdminDashboardStatisticsResponse;
import com.pocketmoney.pocketmoney.dto.AdminIncomeResponse;
import com.pocketmoney.pocketmoney.dto.BalanceResponse;
import com.pocketmoney.pocketmoney.dto.LoanInfo;
import com.pocketmoney.pocketmoney.dto.LoanResponse;
import com.pocketmoney.pocketmoney.dto.MerchantBalanceInfo;
import com.pocketmoney.pocketmoney.dto.MerchantTopUpRequest;
import com.pocketmoney.pocketmoney.dto.MoPayInitiateRequest;
import com.pocketmoney.pocketmoney.dto.MoPayResponse;
import com.pocketmoney.pocketmoney.dto.MomoPaymentRequest;
import com.pocketmoney.pocketmoney.dto.PaginatedResponse;
import com.pocketmoney.pocketmoney.dto.PayLoanRequest;
import com.pocketmoney.pocketmoney.dto.PaymentCategoryResponse;
import com.pocketmoney.pocketmoney.dto.PaymentRequest;
import com.pocketmoney.pocketmoney.dto.PaymentResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverTransactionsResponse;
import com.pocketmoney.pocketmoney.dto.TopUpByPhoneRequest;
import com.pocketmoney.pocketmoney.dto.TopUpRequest;
import com.pocketmoney.pocketmoney.dto.UpdateLoanRequest;
import com.pocketmoney.pocketmoney.dto.UserBonusHistoryResponse;
import com.pocketmoney.pocketmoney.dto.UserResponse;
import com.pocketmoney.pocketmoney.entity.Loan;
import com.pocketmoney.pocketmoney.entity.LoanStatus;
import com.pocketmoney.pocketmoney.entity.MerchantUserBalance;
import com.pocketmoney.pocketmoney.entity.PaymentCategory;
import com.pocketmoney.pocketmoney.entity.PaymentCommissionSetting;
import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import com.pocketmoney.pocketmoney.entity.TopUpType;
import com.pocketmoney.pocketmoney.entity.Transaction;
import com.pocketmoney.pocketmoney.entity.TransactionStatus;
import com.pocketmoney.pocketmoney.entity.TransactionType;
import com.pocketmoney.pocketmoney.entity.User;
import com.pocketmoney.pocketmoney.entity.UserStatus;
import com.pocketmoney.pocketmoney.repository.BalanceAssignmentHistoryRepository;
import com.pocketmoney.pocketmoney.repository.LoanRepository;
import com.pocketmoney.pocketmoney.repository.MerchantUserBalanceRepository;
import com.pocketmoney.pocketmoney.repository.PaymentCategoryRepository;
import com.pocketmoney.pocketmoney.repository.PaymentCommissionSettingRepository;
import com.pocketmoney.pocketmoney.repository.ReceiverRepository;
import com.pocketmoney.pocketmoney.repository.TransactionRepository;
import com.pocketmoney.pocketmoney.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

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
    private final MerchantUserBalanceRepository merchantUserBalanceRepository;
    private final LoanRepository loanRepository;
    private final MoPayService moPayService;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;
    private final MessagingService messagingService;
    private final WhatsAppService whatsAppService;

    public PaymentService(UserRepository userRepository, TransactionRepository transactionRepository,
                         PaymentCategoryRepository paymentCategoryRepository, ReceiverRepository receiverRepository,
                         BalanceAssignmentHistoryRepository balanceAssignmentHistoryRepository,
                         PaymentCommissionSettingRepository paymentCommissionSettingRepository,
                         MerchantUserBalanceRepository merchantUserBalanceRepository,
                         LoanRepository loanRepository,
                         MoPayService moPayService, PasswordEncoder passwordEncoder, EntityManager entityManager,
                         MessagingService messagingService, WhatsAppService whatsAppService) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.paymentCategoryRepository = paymentCategoryRepository;
        this.receiverRepository = receiverRepository;
        this.balanceAssignmentHistoryRepository = balanceAssignmentHistoryRepository;
        this.paymentCommissionSettingRepository = paymentCommissionSettingRepository;
        this.merchantUserBalanceRepository = merchantUserBalanceRepository;
        this.loanRepository = loanRepository;
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

        // Check if this is a merchant-specific top-up
        Receiver receiver = null;
        String transferPhone;
        boolean isMerchantTopUp = false;
        
        if (request.getReceiverId() != null) {
            receiver = receiverRepository.findById(request.getReceiverId())
                    .orElseThrow(() -> new RuntimeException("Receiver not found with ID: " + request.getReceiverId()));
            
            if (receiver.getStatus() != ReceiverStatus.ACTIVE) {
                throw new RuntimeException("Receiver is not active. Status: " + receiver.getStatus());
            }
            
            if (receiver.getMomoAccountPhone() == null || receiver.getMomoAccountPhone().trim().isEmpty()) {
                throw new RuntimeException("Receiver does not have a MoMo account configured for top-ups. Please go to Settings to add your MoMo account phone number.");
            }
            
            // Use merchant's MoMo account
            String normalizedMomoPhone = normalizePhoneTo12Digits(receiver.getMomoAccountPhone());
            transferPhone = normalizedMomoPhone;
            isMerchantTopUp = true;
        } else {
            // Use default admin phone for global top-up
            transferPhone = "250794230137";
        }

        // Create MoPay initiate request
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        moPayRequest.setAmount(request.getAmount());
        moPayRequest.setCurrency("RWF");
        // Normalize phone to 12 digits
        String normalizedPayerPhone = normalizePhoneTo12Digits(request.getPhone());
        moPayRequest.setPhone(normalizedPayerPhone);
        moPayRequest.setPayment_mode("MOBILE");
        moPayRequest.setMessage(request.getMessage() != null ? request.getMessage() : 
            (isMerchantTopUp ? "Top up to " + receiver.getCompanyName() : "Top up to pocket money card"));

        // Create transfer to receiver (merchant's MoMo account or default admin)
        MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
        transfer.setAmount(request.getAmount());
        // transferPhone is String, convert to Long
        Long transferPhoneLong = Long.parseLong(transferPhone);
        transfer.setPhone(transferPhoneLong);
        transfer.setMessage(request.getMessage() != null ? request.getMessage() : 
            (isMerchantTopUp ? "Top up to " + receiver.getCompanyName() : "Top up to pocket money card"));
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

        // Set receiver if this is a merchant-specific top-up
        if (isMerchantTopUp && receiver != null) {
            transaction.setReceiver(receiver);
            transaction.setTopUpType(TopUpType.MOMO);
        }

        // Generate transaction ID starting with POCHI for top-up
        String transactionId = generateTransactionId();
            transaction.setMopayTransactionId(transactionId);
        
        // Check MoPay response - API returns status 201 on success
        String mopayTransactionId = moPayResponse != null ? moPayResponse.getTransactionId() : null;
        if (moPayResponse != null && moPayResponse.getStatus() != null && moPayResponse.getStatus() == 201 
            && mopayTransactionId != null) {
            // Successfully initiated - keep our POCHI transaction ID and set as PENDING
            // Store MoPay transaction ID in message for status checking
            String existingMessage = transaction.getMessage() != null ? transaction.getMessage() : "";
            transaction.setMessage(existingMessage + " | MOPAY_ID:" + mopayTransactionId);
            transaction.setStatus(TransactionStatus.PENDING);
        } else {
            // Initiation failed - mark as FAILED with error message
            transaction.setStatus(TransactionStatus.FAILED);
            String errorMessage = moPayResponse != null && moPayResponse.getMessage() != null 
                ? moPayResponse.getMessage() 
                : "Payment initiation failed - status: " + (moPayResponse != null ? moPayResponse.getStatus() : "null") 
                    + ", mopayTransactionId: " + mopayTransactionId;
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

    /**
     * Merchant-initiated top-up (only main merchants can do this)
     * Supports three types: MOMO, CASH, LOAN
     */
    public PaymentResponse merchantTopUp(MerchantTopUpRequest request) {
        // Get current authenticated receiver from security context
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        
        String currentUsername = authentication.getName();
        logger.info("=== MERCHANT TOP-UP REQUEST ===");
        logger.info("Authenticated username: {}", currentUsername);
        
        Receiver merchant = receiverRepository.findByUsername(currentUsername)
                .orElseThrow(() -> new RuntimeException("Merchant not found with username: " + currentUsername));
        
        logger.info("Found merchant: ID={}, Username={}, Company={}, isFlexible={}, hasParent={}", 
            merchant.getId(), merchant.getUsername(), merchant.getCompanyName(), 
            merchant.getIsFlexible(), merchant.getParentReceiver() != null);
        
        // Verify merchant is a main merchant (not a submerchant)
        if (merchant.getParentReceiver() != null) {
            throw new RuntimeException("Only main merchants can top up money for users. Your merchant account is a submerchant.");
        }
        
        // Check if merchant is flexible
        boolean isFlexible = merchant.getIsFlexible() != null && merchant.getIsFlexible();
        logger.info("Merchant flexible check: isFlexible={}, getIsFlexible()={}", isFlexible, merchant.getIsFlexible());
        
        // Verify merchant is active
        if (merchant.getStatus() != ReceiverStatus.ACTIVE) {
            throw new RuntimeException("Merchant account is not active. Status: " + merchant.getStatus());
        }
        
        // Find user by phone number (phone is linked to the card)
        // Try multiple phone formats since users might be stored in different formats
        String normalizedPhone = normalizePhoneTo12Digits(request.getPhone());
        User user = userRepository.findByPhoneNumber(normalizedPhone).orElse(null);
        
        // If not found with 12-digit format, try with just digits (as stored in database)
        if (user == null) {
            String phoneDigitsOnly = request.getPhone().replaceAll("[^0-9]", "");
            user = userRepository.findByPhoneNumber(phoneDigitsOnly).orElse(null);
        }
        
        // If still not found, try with 0-prefixed format
        if (user == null) {
            String phoneDigitsOnly = request.getPhone().replaceAll("[^0-9]", "");
            if (!phoneDigitsOnly.startsWith("0") && phoneDigitsOnly.length() == 9) {
                user = userRepository.findByPhoneNumber("0" + phoneDigitsOnly).orElse(null);
            }
        }
        
        // If still not found, try extracting last 9 digits and adding 0 prefix
        if (user == null) {
            String phoneDigitsOnly = request.getPhone().replaceAll("[^0-9]", "");
            if (phoneDigitsOnly.length() >= 9) {
                String last9 = phoneDigitsOnly.substring(phoneDigitsOnly.length() - 9);
                user = userRepository.findByPhoneNumber("0" + last9).orElse(null);
            }
        }
        
        // If user doesn't exist, create a new user with minimal information
        if (user == null) {
            logger.info("User not found with phone number: {}. Creating new user automatically.", request.getPhone());
            String phoneDigitsOnly = request.getPhone().replaceAll("[^0-9]", "");
            
            // Create new user with minimal required information
            User newUser = new User();
            newUser.setFullNames("User " + phoneDigitsOnly);
            newUser.setPhoneNumber(normalizedPhone);
            newUser.setPin(passwordEncoder.encode("0000")); // Default PIN, user should change this
            newUser.setIsAssignedNfcCard(false); // No NFC card assigned yet
            newUser.setAmountOnCard(BigDecimal.ZERO);
            newUser.setAmountRemaining(BigDecimal.ZERO);
            newUser.setStatus(UserStatus.ACTIVE);
            
            user = userRepository.save(newUser);
            logger.info("Created new user with ID: {}, Phone: {}", user.getId(), normalizedPhone);
        }
        
        // For merchant top-ups, NFC card is optional (merchant can top up users without cards)
        // Log a warning if no NFC card is assigned, but allow the top-up to proceed
        if (user.getIsAssignedNfcCard() == null || !user.getIsAssignedNfcCard() 
                || user.getNfcCardId() == null || user.getNfcCardId().trim().isEmpty()) {
            logger.warn("User {} does not have an NFC card assigned, but allowing merchant top-up to proceed", user.getPhoneNumber());
        }
        
        // Verify user is active
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new RuntimeException("User account is not active. Status: " + user.getStatus());
        }
        
        // Check if user has a merchant balance record for this merchant
        // If not, create it automatically
        MerchantUserBalance existingMerchantBalance = merchantUserBalanceRepository
                .findByUserIdAndReceiverId(user.getId(), merchant.getId())
                .orElse(null);
        
        if (existingMerchantBalance == null) {
            logger.info("User does not have a merchant balance record with merchant '{}' (ID: {}). Creating it automatically.", 
                merchant.getCompanyName(), merchant.getId());
            
            // Create new merchant balance record
            MerchantUserBalance newMerchantBalance = new MerchantUserBalance();
            newMerchantBalance.setUser(user);
            newMerchantBalance.setReceiver(merchant);
            newMerchantBalance.setBalance(BigDecimal.ZERO);
            newMerchantBalance.setTotalToppedUp(BigDecimal.ZERO);
            
            existingMerchantBalance = merchantUserBalanceRepository.save(newMerchantBalance);
            logger.info("Created merchant balance record: User ID={}, Merchant ID={}, Balance={}", 
                user.getId(), merchant.getId(), existingMerchantBalance.getBalance());
        }
        
        logger.info("User has existing merchant balance: Balance={}, Total Topped Up={}", 
            existingMerchantBalance.getBalance(), existingMerchantBalance.getTotalToppedUp());
        
        // Handle different top-up types
        Transaction transaction = new Transaction();
        transaction.setUser(user);
        transaction.setReceiver(merchant);
        transaction.setTransactionType(TransactionType.TOP_UP);
        transaction.setAmount(request.getAmount());
        transaction.setTopUpType(request.getTopUpType());
        // Set balance before as user's amountRemaining before top-up
        BigDecimal amountRemainingBefore = user.getAmountRemaining();
        transaction.setBalanceBefore(amountRemainingBefore);
        
        // Generate transaction ID starting with POCHI
        String transactionId = generateTransactionId();
        transaction.setMopayTransactionId(transactionId);
        
        if (request.getTopUpType() == TopUpType.MOMO) {
            // MOMO top-up - requires phone number and uses MoPay API
            if (request.getPhone() == null || request.getPhone().trim().isEmpty()) {
                throw new RuntimeException("Phone number is required for MOMO top-up");
            }
            
            // Create MoPay initiate request
            MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
            moPayRequest.setAmount(request.getAmount());
            moPayRequest.setCurrency("RWF");
            String normalizedPayerPhone = normalizePhoneTo12Digits(request.getPhone());
            moPayRequest.setPhone(normalizedPayerPhone);
            moPayRequest.setPayment_mode("MOBILE");
            moPayRequest.setMessage(request.getMessage() != null ? request.getMessage() : 
                "Top up to " + merchant.getCompanyName());
            
            // Determine which phone number to use for receiving top-up money
            // If merchant is flexible, use their MoMo account phone; otherwise use default admin phone
            String receivingPhone;
            if (isFlexible) {
                // Flexible merchant: use their MoMo account phone
                if (merchant.getMomoAccountPhone() == null || merchant.getMomoAccountPhone().trim().isEmpty()) {
                    throw new RuntimeException("Flexible merchant must have a MoMo account configured for top-ups. Please go to Settings to add your MoMo account phone number.");
                }
                receivingPhone = merchant.getMomoAccountPhone();
                logger.info("Merchant is flexible - using merchant's MoMo account phone: {}", receivingPhone);
            } else {
                // Non-flexible merchant: use default admin phone
                receivingPhone = "250794230137";
                logger.info("Merchant is not flexible - using default admin phone: {}", receivingPhone);
            }
            
            // Create transfer to receiving phone (merchant's MoMo account if flexible, or default admin phone)
            MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
            transfer.setAmount(request.getAmount());
            String normalizedReceivingPhone = normalizePhoneTo12Digits(receivingPhone);
            transfer.setPhone(Long.parseLong(normalizedReceivingPhone));
            transfer.setMessage(request.getMessage() != null ? request.getMessage() : 
                "Top up to " + merchant.getCompanyName());
            moPayRequest.setTransfers(java.util.List.of(transfer));
            
            // Initiate payment with MoPay
            MoPayResponse moPayResponse = moPayService.initiatePayment(moPayRequest);
            
            transaction.setPhoneNumber(request.getPhone());
            transaction.setMessage(moPayRequest.getMessage());
            
            // Check MoPay response
            String mopayTransactionId = moPayResponse != null ? moPayResponse.getTransactionId() : null;
            if (moPayResponse != null && moPayResponse.getStatus() != null && moPayResponse.getStatus() == 201 
                && mopayTransactionId != null) {
                // Successfully initiated - keep our POCHI transaction ID and set as PENDING
                String existingMessage = transaction.getMessage() != null ? transaction.getMessage() : "";
                transaction.setMessage(existingMessage + " | MOPAY_ID:" + mopayTransactionId);
                transaction.setStatus(TransactionStatus.PENDING);
            } else {
                // Initiation failed
                transaction.setStatus(TransactionStatus.FAILED);
                String errorMessage = moPayResponse != null && moPayResponse.getMessage() != null 
                    ? moPayResponse.getMessage() 
                    : "Payment initiation failed - status: " + (moPayResponse != null ? moPayResponse.getStatus() : "null");
                transaction.setMessage(errorMessage);
            }
        } else if (request.getTopUpType() == TopUpType.CASH) {
            // CASH top-up - immediate success (no MoPay integration)
            transaction.setStatus(TransactionStatus.SUCCESS);
            transaction.setMessage(request.getMessage() != null ? request.getMessage() : 
                "Cash top-up from " + merchant.getCompanyName());
            transaction.setPhoneNumber(user.getPhoneNumber());
            
            // Immediately update merchant-specific balance
            // merchantBalance already exists (validated above)
            MerchantUserBalance merchantBalance = existingMerchantBalance;
            
            BigDecimal newMerchantBalance = merchantBalance.getBalance().add(request.getAmount());
            merchantBalance.setBalance(newMerchantBalance);
            merchantBalance.setTotalToppedUp(merchantBalance.getTotalToppedUp().add(request.getAmount()));
            merchantUserBalanceRepository.save(merchantBalance);
            
            // Update user's amountRemaining and amountOnCard
            BigDecimal newAmountRemaining = user.getAmountRemaining().add(request.getAmount());
            user.setAmountRemaining(newAmountRemaining);
            user.setAmountOnCard(user.getAmountOnCard().add(request.getAmount()));
            user.setLastTransactionDate(LocalDateTime.now());
            userRepository.save(user);
            
            // Set balance after as user's amountRemaining after top-up
            transaction.setBalanceAfter(newAmountRemaining);
        } else if (request.getTopUpType() == TopUpType.LOAN) {
            // LOAN top-up - immediate success (no MoPay integration)
            transaction.setStatus(TransactionStatus.SUCCESS);
            transaction.setMessage(request.getMessage() != null ? request.getMessage() : 
                "Loan top-up from " + merchant.getCompanyName());
            transaction.setPhoneNumber(user.getPhoneNumber());
            
            // Save transaction first to get the ID
            Transaction savedTransaction = transactionRepository.save(transaction);
            
            // Immediately update merchant-specific balance
            // merchantBalance already exists (validated above)
            MerchantUserBalance merchantBalance = existingMerchantBalance;
            
            BigDecimal newMerchantBalance = merchantBalance.getBalance().add(request.getAmount());
            merchantBalance.setBalance(newMerchantBalance);
            merchantBalance.setTotalToppedUp(merchantBalance.getTotalToppedUp().add(request.getAmount()));
            merchantUserBalanceRepository.save(merchantBalance);
            
            // Update user's amountRemaining and amountOnCard
            BigDecimal newAmountRemaining = user.getAmountRemaining().add(request.getAmount());
            user.setAmountRemaining(newAmountRemaining);
            user.setAmountOnCard(user.getAmountOnCard().add(request.getAmount()));
            user.setLastTransactionDate(LocalDateTime.now());
            userRepository.save(user);
            
            // Set balance after as user's amountRemaining after top-up
            savedTransaction.setBalanceAfter(newAmountRemaining);
            transactionRepository.save(savedTransaction);
            
            // Create loan record
            Loan loan = new Loan();
            loan.setUser(user);
            loan.setReceiver(merchant);
            loan.setTransaction(savedTransaction);
            loan.setLoanAmount(request.getAmount());
            loan.setPaidAmount(BigDecimal.ZERO);
            loan.setRemainingAmount(request.getAmount());
            loan.setStatus(LoanStatus.PENDING);
            loanRepository.save(loan);
            
            return mapToPaymentResponse(savedTransaction);
        } else {
            throw new RuntimeException("Invalid top-up type: " + request.getTopUpType());
        }
        
        Transaction savedTransaction = transactionRepository.save(transaction);
        return mapToPaymentResponse(savedTransaction);
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
        
        // Each receiver (main merchant or submerchant) has its own separate balance
        // Submerchants do NOT share balances with main merchant - they have their own
        Receiver balanceOwner = receiver; // Always use receiver's own balance, not parent's

        // Log receiver and user balances for debugging
        logger.info("=== RECEIVER AND USER BALANCE CHECK ===");
        logger.info("Receiver ID: {}", receiver.getId());
        logger.info("Receiver Company Name: {}", receiver.getCompanyName());
        logger.info("Receiver Wallet Balance: {}", receiver.getWalletBalance());
        logger.info("Receiver Total Received: {}", receiver.getTotalReceived());
        logger.info("Receiver Assigned Balance: {}", receiver.getAssignedBalance());
        logger.info("Receiver Remaining Balance: {}", receiver.getRemainingBalance());
        logger.info("Receiver Balance Owner ID: {} (same as receiver)", balanceOwner.getId());
        logger.info("Receiver Balance Owner Company Name: {}", balanceOwner.getCompanyName());
        logger.info("Receiver Remaining Balance: {}", balanceOwner.getRemainingBalance());
        logger.info("User ID: {}", user.getId());
        logger.info("User Full Names: {}", user.getFullNames());
        logger.info("User Phone: {}", user.getPhoneNumber());
        logger.info("User Amount On Card: {}", user.getAmountOnCard());
        logger.info("User Amount Remaining: {}", user.getAmountRemaining());

        // Verify PIN
        if (!passwordEncoder.matches(request.getPin(), user.getPin())) {
            throw new RuntimeException("Invalid PIN");
        }

        // Check balance - prioritize merchant-specific balance if available
        // If receiver is a submerchant, also check balance with parent (main merchant)
        BigDecimal merchantBalance = BigDecimal.ZERO;
        MerchantUserBalance merchantUserBalance = merchantUserBalanceRepository
                .findByUserIdAndReceiverId(userId, receiver.getId())
                .orElse(null);
        
        // If no balance found and receiver is a submerchant, check with parent (main merchant)
        if (merchantUserBalance == null && receiver.getParentReceiver() != null) {
            logger.info("No balance found with submerchant {}, checking with parent merchant {}", 
                receiver.getId(), receiver.getParentReceiver().getId());
            merchantUserBalance = merchantUserBalanceRepository
                    .findByUserIdAndReceiverId(userId, receiver.getParentReceiver().getId())
                    .orElse(null);
        }
        
        logger.info("=== PAYMENT BALANCE CHECK ===");
        logger.info("User ID: {}", userId);
        logger.info("Receiver ID: {}", receiver.getId());
        logger.info("Receiver is submerchant: {}", receiver.getParentReceiver() != null);
        if (receiver.getParentReceiver() != null) {
            logger.info("Parent merchant ID: {}", receiver.getParentReceiver().getId());
        }
        logger.info("MerchantUserBalance exists: {}", merchantUserBalance != null);
        
        // Check if receiver itself is flexible (needed for loan check)
        boolean isReceiverFlexible = receiver.getIsFlexible() != null && receiver.getIsFlexible();
        boolean hasMerchantBalance = merchantUserBalance != null;
        
        // Check main merchant flexible status early (needed for balance calculation)
        Receiver mainMerchantForCheck = receiver;
        if (receiver.getParentReceiver() != null) {
            mainMerchantForCheck = receiver.getParentReceiver();
        }
        boolean isMainMerchantFlexibleForBalance = mainMerchantForCheck.getIsFlexible() != null && mainMerchantForCheck.getIsFlexible();
        
        logger.info("=== FLEXIBLE CHECK ===");
        logger.info("hasMerchantBalance: {}", hasMerchantBalance);
        logger.info("isReceiverFlexible: {}", isReceiverFlexible);
        logger.info("isMainMerchantFlexibleForBalance: {}", isMainMerchantFlexibleForBalance);
        
        if (merchantUserBalance != null) {
            merchantBalance = merchantUserBalance.getBalance();
            logger.info("Initial merchant balance: {}", merchantBalance);
            
            // Get the merchant who did the top-up (from merchantUserBalance)
            Receiver topUpMerchant = merchantUserBalance.getReceiver();
            Receiver mainTopUpMerchant = topUpMerchant;
            if (topUpMerchant.getParentReceiver() != null) {
                mainTopUpMerchant = topUpMerchant.getParentReceiver();
            }
            boolean isTopUpMerchantFlexible = mainTopUpMerchant.getIsFlexible() != null && mainTopUpMerchant.getIsFlexible();
            
            logger.info("Top-up merchant: {} (ID: {}), Main merchant: {} (ID: {}), Is flexible: {}", 
                topUpMerchant.getCompanyName(), topUpMerchant.getId(),
                mainTopUpMerchant.getCompanyName(), mainTopUpMerchant.getId(), isTopUpMerchantFlexible);
            
            // In flexible mode, skip loan check - let topped up money go straight to balance
            if (isTopUpMerchantFlexible) {
                logger.info("Top-up merchant is in FLEXIBLE mode - skipping loan check, allowing full balance usage");
                // Don't modify merchantBalance, allow full balance to be used
            } else {
                // NON-FLEXIBLE mode: Check loans if the balance came from a LOAN top-up
                // Check if there's a loan for this user-receiver pair
                List<Loan> userLoans = loanRepository.findByUserIdAndReceiverId(userId, topUpMerchant.getId());
                logger.info("Found {} loans for user-topUpMerchant pair", userLoans.size());
                
                if (!userLoans.isEmpty()) {
                    // There is a loan - this means the top-up was a LOAN
                    // Check if the loan is still outstanding (remaining >= 1) and not completed
                    Loan loan = userLoans.get(0);
                    logger.info("Loan ID: {}, Status: {}, Remaining: {}", 
                        loan.getId(), loan.getStatus(), loan.getRemainingAmount());
                    
                    if (loan.getStatus() != LoanStatus.COMPLETED) {
                        // Loan is not completed - check if it's still outstanding
                        if (loan.getRemainingAmount().compareTo(new BigDecimal("1")) >= 0) {
                            // Loan remaining >= 1, don't allow using merchant balance
                            logger.info("Loan remaining >= 1, blocking merchant balance (loan not paid)");
                            merchantBalance = BigDecimal.ZERO;
                        } else {
                            // Loan remaining < 1, allow using merchant balance
                            logger.info("Loan remaining < 1, allowing merchant balance");
                        }
                    } else {
                        // Loan is completed, allow using merchant balance
                        logger.info("Loan is completed, allowing merchant balance");
                    }
                } else {
                    // No loan exists - this means the top-up was CASH or MOMO
                    // Don't check loans, allow merchant balance usage
                    logger.info("No loan found - top-up was CASH or MOMO, allowing merchant balance");
                }
            }
            
            logger.info("Final merchant balance after loan check: {}", merchantBalance);
        } else {
            logger.info("No MerchantUserBalance found for user-receiver pair");
        }
        
        BigDecimal globalBalance = user.getAmountRemaining();
        logger.info("User global balance (amountRemaining): {}", globalBalance);
        
        // Get ALL merchant balances for this user and sum them up
        // All merchant balances should be added to amountRemaining for total available balance
        List<MerchantUserBalance> allMerchantBalances = merchantUserBalanceRepository.findByUserId(userId);
        BigDecimal totalAllMerchantBalances = BigDecimal.ZERO;
        
        logger.info("=== ALL MERCHANT BALANCES FOR USER ===");
        for (MerchantUserBalance mb : allMerchantBalances) {
            logger.info("Merchant: {} (ID: {}), Balance: {}, Total Topped Up: {}", 
                mb.getReceiver().getCompanyName(), mb.getReceiver().getId(), 
                mb.getBalance(), mb.getTotalToppedUp());
            // Sum all merchant balances
            totalAllMerchantBalances = totalAllMerchantBalances.add(mb.getBalance());
        }
        logger.info("Total of all merchant balances: {}", totalAllMerchantBalances);
        
        // Calculate total available balance
        // Total available = global balance (amountRemaining) + all merchant balances
        BigDecimal totalAvailableBalance = globalBalance.add(totalAllMerchantBalances);
        
        // If user has been topped up by the current merchant (LOAN, CASH, or MOMO), also add topped up amount
        // This gives users access to the full topped-up amount (whether it's LOAN, CASH, or MOMO)
        if (hasMerchantBalance) {
            BigDecimal amountToAdd = merchantUserBalance.getTotalToppedUp();
            
            if (isMainMerchantFlexibleForBalance) {
                logger.info("User topped up by flexible merchant - adding topped up amount {} to available balance", amountToAdd);
            } else {
                logger.info("User topped up by merchant - adding topped up amount {} to available balance", amountToAdd);
            }
            
            // Add the topped up amount to total available balance
            totalAvailableBalance = totalAvailableBalance.add(amountToAdd);
            logger.info("Total available balance with topped up amount: {}", totalAvailableBalance);
        }
        
        logger.info("Final total available balance: {} (Global: {} + All Merchant Balances: {})", 
            totalAvailableBalance, globalBalance, totalAllMerchantBalances);
        logger.info("Payment amount requested: {}", request.getAmount());
        logger.info("=== ALL MERCHANT BALANCES FOR USER ===");
        for (MerchantUserBalance mb : allMerchantBalances) {
            logger.info("Merchant: {} (ID: {}), Balance: {}, Total Topped Up: {}", 
                mb.getReceiver().getCompanyName(), mb.getReceiver().getId(), 
                mb.getBalance(), mb.getTotalToppedUp());
        }
        
        // Check if main merchant is in flexible mode (already calculated above as isMainMerchantFlexibleForBalance)
        // Use the already calculated value
        boolean isMainMerchantFlexible = isMainMerchantFlexibleForBalance;
        logger.info("Main merchant is flexible mode: {} (Main merchant: {})", isMainMerchantFlexible, mainMerchantForCheck.getCompanyName());
        
        // If main merchant is flexible, skip all balance checks and allow payment
        // BUT still deduct from user balance if they have one
        if (isMainMerchantFlexible) {
            logger.info("Main merchant is in FLEXIBLE mode - allowing payment without balance checks");
            // Skip balance validation checks, but keep the balance values for deduction
            // Don't overwrite balances - we need to deduct from user if they have balance
            // The balances are already calculated above, keep them for deduction logic
        } else {
            // Main merchant is NON-FLEXIBLE: Check balances as usual
            // Check receiver's remaining balance - if user has no balance but receiver has balance, allow payment
            BigDecimal receiverRemainingBalance = balanceOwner.getRemainingBalance() != null ? balanceOwner.getRemainingBalance() : BigDecimal.ZERO;
            logger.info("Receiver remaining balance: {}", receiverRemainingBalance);
            
            // Payment logic based on flexible mode:
            // - FLEXIBLE: Only check user balance, ignore receiver balance
            // - NON-FLEXIBLE: Check both user balance AND receiver balance
            boolean userHasBalance = totalAvailableBalance.compareTo(request.getAmount()) >= 0;
            boolean receiverHasBalance = receiverRemainingBalance.compareTo(request.getAmount()) >= 0;
            
            logger.info("User has sufficient balance: {}", userHasBalance);
            logger.info("Receiver has sufficient balance: {}", receiverHasBalance);
            
            // EXCEPTION: If user has been topped up by this merchant (or parent merchant) AND merchant is flexible, allow payment without balance checks
            // For submerchants, check main merchant flexible status since users can be topped up by main merchant
            boolean merchantFlexibleToCheck = receiver.getParentReceiver() != null ? isMainMerchantFlexibleForBalance : isReceiverFlexible;
            if (hasMerchantBalance && merchantFlexibleToCheck) {
                // User has been topped up by this merchant (or parent merchant) and merchant is flexible
                // Allow payment without checking any balance (user balance or receiver balance)
                logger.info("User has merchant balance and merchant is flexible - allowing payment without any balance checks");
                // Payment will proceed, deducting from merchant balance or global balance if available
            } else if (isReceiverFlexible) {
                // RECEIVER FLEXIBLE MODE: Only check user balance, ignore receiver balance
                logger.info("Receiver is in FLEXIBLE mode - only checking user balance");
                if (!userHasBalance) {
                    String errorMsg = String.format(
                        "Insufficient balance. User balance: %s (Merchant: %s, Global: %s), Required: %s. " +
                        "Please ensure you have sufficient balance in your account or top up from this merchant.",
                        totalAvailableBalance, merchantBalance, globalBalance, request.getAmount());
                    logger.error("=== INSUFFICIENT BALANCE ERROR (FLEXIBLE MODE) ===");
                    logger.error(errorMsg);
                    logger.error("User amountRemaining from DB: {}", user.getAmountRemaining());
                    logger.error("Merchant balance from DB: {}", merchantBalance);
                    throw new RuntimeException(errorMsg);
                }
            } else {
                // NON-FLEXIBLE MODE: Receiver can process payments even when assignedBalance is 0
                // They need to sell, and when admin assigns assignedBalance, it's for users to consume
                // So we only check user balance, not receiver balance
                logger.info("Receiver is in NON-FLEXIBLE mode - checking user balance only (receiver can sell even with assignedBalance=0)");
                // Allow payment if user has sufficient balance (merchant + global)
                // Receiver's assignedBalance is for users to consume, not for blocking sales
                if (!userHasBalance) {
                    String errorMsg = String.format(
                        "Insufficient balance. User balance: %s (Merchant: %s, Global: %s), Required: %s. " +
                        "Please ensure you have sufficient balance in your account.",
                        totalAvailableBalance, merchantBalance, globalBalance, request.getAmount());
                    logger.error("=== INSUFFICIENT BALANCE ERROR (NON-FLEXIBLE MODE) ===");
                    logger.error(errorMsg);
                    logger.error("User amountRemaining from DB: {}", user.getAmountRemaining());
                    logger.error("Merchant balance from DB: {}", merchantBalance);
                    logger.info("Note: Receiver can process payments even when assignedBalance is 0 - they need to sell. AssignedBalance is for users to consume.");
                    throw new RuntimeException(errorMsg);
                }
            }
        }

        BigDecimal paymentAmount = request.getAmount();
        
        // Track balance before deductions
        BigDecimal merchantBalanceBefore = merchantBalance;
        BigDecimal globalBalanceBefore = globalBalance;
        
        // Calculate discount and bonus amounts (use balance owner's percentages)
        BigDecimal discountPercentage = balanceOwner.getDiscountPercentage() != null ? balanceOwner.getDiscountPercentage() : BigDecimal.ZERO;
        BigDecimal userBonusPercentage = balanceOwner.getUserBonusPercentage() != null ? balanceOwner.getUserBonusPercentage() : BigDecimal.ZERO;
        
        // Calculate discount/charge amount (based on payment amount) - This is the TOTAL charge (e.g., 10%)
        BigDecimal discountAmount = paymentAmount.multiply(discountPercentage).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        
        // Calculate user bonus amount (e.g., 2% of payment amount)
        BigDecimal userBonusAmount = paymentAmount.multiply(userBonusPercentage).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        
        // Calculate admin income amount (e.g., 8% = 10% - 2%)
        BigDecimal adminIncomeAmount = discountAmount.subtract(userBonusAmount);
        
        // Use receiver's own remaining balance (each receiver has separate balance)
        // Receiver's remaining balance is deducted by ONLY the payment amount (discount was already added as bonus when balance was assigned)
        // Example: User pays 500, deduct only 500 from receiver balance (no discount deduction)
        // Check if receiver has sufficient remaining balance BEFORE processing payment
        BigDecimal receiverBalanceBefore = receiver.getRemainingBalance() != null ? receiver.getRemainingBalance() : BigDecimal.ZERO;
        BigDecimal receiverBalanceReduction = paymentAmount; // Only deduct payment amount, not payment + discount
        BigDecimal receiverBalanceAfter = receiverBalanceBefore.subtract(receiverBalanceReduction);
        
        // Check if remaining balance would be below 1 after this transaction
        // Skip this check if main merchant is flexible
        if (!isMainMerchantFlexible && receiverBalanceAfter.compareTo(new BigDecimal("1")) < 0) {
            // Check if user has merchant-specific balance for this receiver
            // If user doesn't have merchant balance, they need to top up with this merchant
            // Note: hasMerchantBalance is already declared above
            
            if (!hasMerchantBalance) {
                // User doesn't have merchant-specific balance - they are not working with this merchant
                throw new RuntimeException("Please get a card from us and top up with us to be able to use our service otherwise");
            } else {
                // User has merchant balance but receiver balance is low - show BeFosot message
                throw new RuntimeException("Contact BeFosot to top up for your company. Your card has sufficient balance, but the company's selling balance is low.");
            }
        }
        
        // For PAYMENT: Direct internal transfer (no MoPay integration needed)
        // Note: Balance validation already done above using totalAvailableBalance (global + merchant balances)
        // This allows users topped up by main merchant to pay at submerchants
        
        // Get the actual user balance from database for deduction (don't use the modified totalAvailableBalance)
        // We need to preserve the actual balance values for deduction even in flexible mode
        BigDecimal actualGlobalBalance = user.getAmountRemaining() != null ? user.getAmountRemaining() : BigDecimal.ZERO;
        BigDecimal actualMerchantBalance = merchantUserBalance != null && merchantUserBalance.getBalance() != null 
            ? merchantUserBalance.getBalance() : BigDecimal.ZERO;
        BigDecimal actualTotalAvailableBalance = actualGlobalBalance.add(actualMerchantBalance);
        
        // Check if user has sufficient balance to cover the full payment amount
        // User must have enough balance - throw error if insufficient
        if (actualTotalAvailableBalance.compareTo(paymentAmount) < 0) {
            String errorMsg = String.format(
                "Insufficient balance. You have %s (Merchant: %s, Global: %s), but payment requires %s. " +
                "Please top up your account to have sufficient balance.",
                actualTotalAvailableBalance, actualMerchantBalance, actualGlobalBalance, paymentAmount);
            logger.error("=== INSUFFICIENT BALANCE ERROR ===");
            logger.error(errorMsg);
            logger.error("User amountRemaining from DB: {}", actualGlobalBalance);
            logger.error("Merchant balance from DB: {}", actualMerchantBalance);
            throw new RuntimeException(errorMsg);
        }
        
        // Deduct from merchant-specific balance first, then global balance
        BigDecimal remainingPayment = paymentAmount;
        BigDecimal userNewBalance = actualGlobalBalance; // Initialize with actual balance for deduction
        
        // Deduct from user balance - user has sufficient balance (already validated above)
        if (actualTotalAvailableBalance.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal amountDeductedFromMerchant = BigDecimal.ZERO;
            BigDecimal amountDeductedFromUserGlobal = BigDecimal.ZERO;
            
            // SIMPLIFIED APPROACH: Always deduct from user's global amountRemaining only
            // Merchant balances are just records of what was topped up - they don't get deducted
            // When top-up happens, we add to both merchant balance AND user's global amountRemaining
            // When payment happens, we deduct from user's global amountRemaining only
            // This way, user's global amountRemaining is the single source of truth for actual balance
            logger.info("Deducting from user's global amountRemaining only (merchant balances are records only)");
            
            // Deduct from user's global balance (amountRemaining) only
            if (actualGlobalBalance.compareTo(BigDecimal.ZERO) > 0) {
                if (actualGlobalBalance.compareTo(remainingPayment) >= 0) {
                    // User has enough balance to cover entire payment
                    amountDeductedFromUserGlobal = remainingPayment;
                    userNewBalance = actualGlobalBalance.subtract(remainingPayment);
                    remainingPayment = BigDecimal.ZERO;
                } else {
                    // User has some balance but not enough - deduct only what they have, stop at 0
                    amountDeductedFromUserGlobal = actualGlobalBalance;
                    userNewBalance = BigDecimal.ZERO; // User balance goes to 0, not negative
                    remainingPayment = remainingPayment.subtract(actualGlobalBalance);
                    logger.info("User balance ({}) is less than remaining payment. Deducted only what user has. Remaining payment: {} will NOT be covered - user can only pay what they have.", 
                        actualGlobalBalance, remainingPayment);
                }
                user.setAmountRemaining(userNewBalance);
                logger.info("User global amountRemaining deducted: {}, New user balance: {}", 
                    amountDeductedFromUserGlobal, userNewBalance);
            } else {
                // No user balance to deduct from
                userNewBalance = actualGlobalBalance; // Keep balance as is (0)
            }
            
            // Merchant balance is NOT deducted - it stays as a record of what was topped up
            // The actual balance is tracked in user's global amountRemaining
            
            // If there's still remaining payment after deducting from merchant and user balance,
            // and we're in flexible mode, check if user has no balance left - payment should only use what user has
            if (remainingPayment.compareTo(BigDecimal.ZERO) > 0) {
                // User doesn't have enough balance to cover the full payment
                // In this case, we only process payment for what the user can pay (merchant balance + user balance)
                BigDecimal actualPaymentAmount = paymentAmount.subtract(remainingPayment);
                logger.info("User can only pay {} out of {} requested. Payment processed for amount user has available.", actualPaymentAmount, paymentAmount);
                // Note: remainingPayment amount will not be deducted from receiver, payment is only for what user has
            }
            
            // Add user bonus back to user's wallet if applicable
            if (userBonusAmount.compareTo(BigDecimal.ZERO) > 0) {
                userNewBalance = userNewBalance.add(userBonusAmount);
                // Ensure amountRemaining never goes below 0 (though bonus should make it positive)
                if (userNewBalance.compareTo(BigDecimal.ZERO) < 0) {
                    userNewBalance = BigDecimal.ZERO;
                }
                user.setAmountRemaining(userNewBalance);
            }
            
            user.setLastTransactionDate(LocalDateTime.now());
            userRepository.save(user);
            
            logger.info("Payment deduction: Merchant balance deducted: {}, User amountRemaining deducted: {}, Remaining payment: {}", 
                amountDeductedFromMerchant, amountDeductedFromUserGlobal, remainingPayment);
        } else {
            // User has no balance - they cannot pay anything
            logger.info("User has no balance - cannot process payment. User can only pay what they have.");
            // Keep userNewBalance as actualGlobalBalance (0) for message purposes
            userNewBalance = actualGlobalBalance;
            remainingPayment = paymentAmount; // Full payment amount - user has no balance to pay
        }
        
        // Calculate actual amount paid by user (merchant balance + user global balance)
        // User can only pay what they have - don't allow payment beyond their balance
        BigDecimal actualAmountPaidByUser = paymentAmount.subtract(remainingPayment);
        
        // If there's remaining payment after deducting all user has, 
        // user can only pay what they have - don't allow payment beyond their balance
        if (remainingPayment.compareTo(BigDecimal.ZERO) > 0) {
            logger.info("User does not have sufficient balance. Payment amount requested: {}, User can pay: {}", 
                paymentAmount, actualAmountPaidByUser);
            // Don't deduct remaining payment from receiver - user can only pay what they have
            // The payment will be processed only for the amount the user could pay
        }

        // Update receiver's own remaining balance (each receiver has separate balance)
        // Receiver's remaining balance is reduced by the payment amount
        // Note: receiverBalanceBefore, receiverBalanceReduction, and receiverBalanceAfter were already calculated earlier
        // Update with the payment amount (user has sufficient balance, so full payment)
        receiverBalanceReduction = paymentAmount; // Deduct full payment amount (user has sufficient balance)
        receiverBalanceAfter = receiverBalanceBefore.subtract(receiverBalanceReduction);
        
        // Ensure remaining balance never goes below 0
        if (receiverBalanceAfter.compareTo(BigDecimal.ZERO) < 0) {
            receiverBalanceAfter = BigDecimal.ZERO;
            logger.info("Receiver balance would have been negative, setting to 0");
        }
        receiver.setRemainingBalance(receiverBalanceAfter);
        logger.info("Updated receiver balance to {} (deducted {} from {})", 
            receiverBalanceAfter, receiverBalanceReduction, receiverBalanceBefore);
        receiver.setLastTransactionDate(LocalDateTime.now());
        
        // Update receiver's wallet balance and total received (each receiver has separate balance)
        BigDecimal actualPaymentReceived = paymentAmount;
        BigDecimal receiverNewWallet = receiver.getWalletBalance().add(actualPaymentReceived);
        BigDecimal receiverNewTotal = receiver.getTotalReceived().add(actualPaymentReceived);
        receiver.setWalletBalance(receiverNewWallet);
        receiver.setTotalReceived(receiverNewTotal);
        receiverRepository.save(receiver);
        
        logger.info("Updated receiver '{}' balance: wallet={}, totalReceived={}, remainingBalance={}", 
            receiver.getCompanyName(), receiverNewWallet, receiverNewTotal, receiverBalanceAfter);

        // Create transaction record - PAYMENT is immediate (no MoPay, internal transfer)
        Transaction transaction = new Transaction();
        transaction.setUser(user);
        transaction.setPaymentCategory(paymentCategory);
        transaction.setReceiver(receiver);
        transaction.setTransactionType(TransactionType.PAYMENT);
        transaction.setAmount(paymentAmount);
        transaction.setPhoneNumber(receiver.getReceiverPhone());
        transaction.setMessage(request.getMessage() != null ? request.getMessage() : "Payment from pocket money card");
        // Set balance before as the user's amountRemaining (global balance) before payment
        transaction.setBalanceBefore(globalBalanceBefore);
        // Set balance after as the user's amountRemaining (global balance) after payment deduction and bonus
        // Get the current value from user entity after all updates (includes deduction and bonus if any)
        BigDecimal finalAmountRemaining = user.getAmountRemaining();
        transaction.setBalanceAfter(finalAmountRemaining);
        transaction.setDiscountAmount(discountAmount);
        transaction.setUserBonusAmount(userBonusAmount);
        transaction.setAdminIncomeAmount(adminIncomeAmount);
        transaction.setReceiverBalanceBefore(receiverBalanceBefore); // Receiver's balance before
        transaction.setReceiverBalanceAfter(receiverBalanceAfter); // Receiver's balance after
        transaction.setStatus(TransactionStatus.SUCCESS); // Immediate success for internal transfers
        
        // Generate transaction ID for NFC card payments (POCHI format)
        // This ensures all payments have a transaction ID for tracking
        String nfcTransactionId = generateTransactionId();
        transaction.setMopayTransactionId(nfcTransactionId);
        logger.info("Generated NFC card payment transaction ID: {}", nfcTransactionId);

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
        // Each receiver (main merchant or submerchant) has its own separate balance
        Receiver balanceOwner = receiver; // Always use receiver's own balance

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
        
        logger.info("Setting payer phone in MoPay request to: {} (String value)", normalizedPayerPhone);
        moPayRequest.setPhone(normalizedPayerPhone);
        moPayRequest.setPayment_mode("MOBILE");
        moPayRequest.setMessage(request.getMessage() != null ? request.getMessage() : "Payment to " + receiver.getCompanyName());
        
        // Create transfer to receiver - amount minus user bonus and commission
        // The remaining amount (paymentAmount - userBonusAmount - commissionAmount) goes to receiver
        BigDecimal receiverAmount = paymentAmount.subtract(userBonusAmount).subtract(commissionAmount);
        
        logger.info("=== MOMO PAYMENT TRANSFER CALCULATION ===");
        logger.info("Payment amount: {}, User bonus amount: {}, Commission amount: {}, Receiver amount: {}", 
                paymentAmount, userBonusAmount, commissionAmount, receiverAmount);
        
        // Determine which phone number to use for receiving payment money
        // If receiver is flexible, use their MoMo account phone; otherwise use default admin phone
        boolean isReceiverFlexible = receiver.getIsFlexible() != null && receiver.getIsFlexible();
        String receivingPhone;
        if (isReceiverFlexible) {
            // Flexible receiver: use their MoMo account phone
            if (receiver.getMomoAccountPhone() == null || receiver.getMomoAccountPhone().trim().isEmpty()) {
                throw new RuntimeException("Flexible receiver must have a MoMo account configured to receive payments. Please go to Settings to add your MoMo account phone number.");
            }
            receivingPhone = receiver.getMomoAccountPhone();
            logger.info("Receiver is flexible - using receiver's MoMo account phone: {}", receivingPhone);
        } else {
            // Non-flexible receiver: use default admin phone
            receivingPhone = "250794230137";
            logger.info("Receiver is not flexible - using default admin phone: {}", receivingPhone);
        }
        
        MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
        transfer.setAmount(receiverAmount);
        logger.info("Using receiving phone for payment: {}", receivingPhone);
        
        // Normalize receiving phone to 12 digits - ensure it's exactly 12 digits
        String normalizedReceivingPhone = normalizePhoneTo12Digits(receivingPhone);
        
        logger.info("Receiving phone normalization - Original: '{}', Normalized: '{}' ({} digits)", receivingPhone, normalizedReceivingPhone, normalizedReceivingPhone.length());
        
        // Validate the normalized phone is exactly 12 digits
        if (normalizedReceivingPhone.length() != 12) {
            throw new RuntimeException("Receiving phone number must be normalized to exactly 12 digits. Original: '" + receivingPhone + "', Normalized: '" + normalizedReceivingPhone + "' (" + normalizedReceivingPhone.length() + " digits)");
        }
        
        // Validate it's numeric only
        if (!normalizedReceivingPhone.matches("^[0-9]{12}$")) {
            throw new RuntimeException("Normalized receiving phone must contain only digits. Got: '" + normalizedReceivingPhone + "'");
        }
        
        logger.info("Setting receiving phone in transfer to: {} (Long value), Amount: {} (payment {} minus bonus {} minus commission {})", 
                normalizedReceivingPhone, receiverAmount, paymentAmount, userBonusAmount, commissionAmount);
        transfer.setPhone(Long.parseLong(normalizedReceivingPhone));
        String payerName = user != null ? user.getFullNames() : "Guest User";
        transfer.setMessage("Payment from " + payerName + " to " + receiver.getCompanyName());
        
        // Build transfers list - admin payment (minus bonus and commission), user bonus back to payer, and commission (if applicable)
        java.util.List<MoPayInitiateRequest.Transfer> transfers = new java.util.ArrayList<>();
        transfers.add(transfer);
        
        logger.info("Added receiver transfer (to {} phone) - Amount: {}, Phone: {}", 
                isReceiverFlexible ? "receiver's MoMo account" : "default admin", receiverAmount, normalizedReceivingPhone);
        
        // Add transfer back to payer with user bonus (similar to /api/payments/pay)
        logger.info("Checking if user bonus should be added - User bonus amount: {}, Is greater than zero: {}", 
                userBonusAmount, userBonusAmount.compareTo(BigDecimal.ZERO) > 0);
        
        if (userBonusAmount.compareTo(BigDecimal.ZERO) > 0) {
            MoPayInitiateRequest.Transfer bonusTransfer = new MoPayInitiateRequest.Transfer();
            bonusTransfer.setAmount(userBonusAmount);
            bonusTransfer.setPhone(Long.parseLong(normalizedPayerPhone));
            bonusTransfer.setMessage("User bonus for payment to " + receiver.getCompanyName());
            transfers.add(bonusTransfer);
            logger.info("✅ Added user bonus transfer back to payer - Amount: {}, Phone: {}", userBonusAmount, normalizedPayerPhone);
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
            
            MoPayInitiateRequest.Transfer commissionTransfer = new MoPayInitiateRequest.Transfer();
            commissionTransfer.setAmount(commissionAmount);
            commissionTransfer.setPhone(Long.parseLong(normalizedCommissionPhone));
            commissionTransfer.setMessage("Commission for payment to " + receiver.getCompanyName());
            transfers.add(commissionTransfer);
            logger.info("✅ Added commission transfer to commissioner - Amount: {}, Phone: {}", commissionAmount, normalizedCommissionPhone);
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
        transaction.setReceiverBalanceBefore(receiverBalanceBefore); // Receiver's balance before
        transaction.setReceiverBalanceAfter(receiverBalanceAfter); // Receiver's balance after (to be applied on SUCCESS)

        // Generate transaction ID starting with POCHI for MOMO payments
        String transactionId = generateTransactionId();
            transaction.setMopayTransactionId(transactionId);
        
        // Check MoPay response - API returns status 201 on success
        String mopayTransactionId = moPayResponse != null ? moPayResponse.getTransactionId() : null;
        if (moPayResponse != null && moPayResponse.getStatus() != null && moPayResponse.getStatus() == 201 
            && mopayTransactionId != null) {
            // Successfully initiated - keep our POCHI transaction ID and set as PENDING
            // Store MoPay transaction ID in message for status checking
            String existingMessage = transaction.getMessage() != null ? transaction.getMessage() : "";
            transaction.setMessage(existingMessage + " | MOPAY_ID:" + mopayTransactionId);
            transaction.setStatus(TransactionStatus.PENDING);
            
            // Note: SMS and WhatsApp notifications will be sent only when payment is confirmed (SUCCESS)
            // See checkTransactionStatus method for notification logic
        } else {
            // Initiation failed - mark as FAILED with error message
            transaction.setStatus(TransactionStatus.FAILED);
            String errorMessage = moPayResponse != null && moPayResponse.getMessage() != null 
                ? moPayResponse.getMessage() 
                : "Payment initiation failed - status: " + (moPayResponse != null ? moPayResponse.getStatus() : "null") 
                    + ", mopayTransactionId: " + mopayTransactionId;
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
        // We display totalBonusReceived separately for tracking purposes

        // Calculate amountRemaining: in flexible mode, use sum of flexible merchant balances
        BigDecimal amountRemaining = calculateAmountRemaining(user);
        
        // Note: amountRemaining already includes bonuses that were added back during payment processing
        // amountRemainingWithBonus shows the effective balance (which already has bonuses included)
        // We display totalBonusReceived separately for tracking purposes
        // For flexible mode, amountRemainingWithBonus should also reflect flexible merchant balances
        // In flexible mode, amountRemaining already reflects the merchant balances, so use it for both
        BigDecimal amountRemainingWithBonus = amountRemaining;

        // Get merchant-specific balances
        List<MerchantUserBalance> merchantBalances = merchantUserBalanceRepository.findByUserId(userId);
        List<MerchantBalanceInfo> merchantBalanceInfos = merchantBalances.stream()
                .map(mb -> {
                    MerchantBalanceInfo info = new MerchantBalanceInfo();
                    info.setReceiverId(mb.getReceiver().getId());
                    info.setReceiverCompanyName(mb.getReceiver().getCompanyName());
                    info.setBalance(mb.getBalance());
                    info.setTotalToppedUp(mb.getTotalToppedUp());
                    return info;
                })
                .collect(java.util.stream.Collectors.toList());

        BalanceResponse response = new BalanceResponse();
        response.setUserId(user.getId());
        response.setFullNames(user.getFullNames());
        response.setPhoneNumber(user.getPhoneNumber());
        response.setEmail(user.getEmail());
        response.setIsAssignedNfcCard(user.getIsAssignedNfcCard());
        response.setNfcCardId(user.getNfcCardId());
        response.setAmountOnCard(user.getAmountOnCard());
        response.setAmountRemaining(amountRemaining); // Includes merchant balance if merchant is flexible
        response.setTotalBonusReceived(totalBonusReceived);
        response.setAmountRemainingWithBonus(amountRemainingWithBonus); // Updated to include merchant balance in flexible mode
        response.setMerchantBalances(merchantBalanceInfos);
        response.setStatus(user.getStatus());
        response.setLastTransactionDate(user.getLastTransactionDate());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }

    public PaymentResponse checkTransactionStatus(String mopayTransactionId) {
        Transaction transaction = transactionRepository.findByMopayTransactionIdWithUser(mopayTransactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        // CRITICAL: Transaction ID must NEVER change - it's immutable after creation
        String originalTransactionId = transaction.getMopayTransactionId();
        logger.info("Checking transaction status - Transaction ID: {}, Current Status: {}, Requested ID: {}", 
            originalTransactionId, transaction.getStatus(), mopayTransactionId);

        // For NFC card payments (POCHI transaction IDs), transaction is already SUCCESS
        // No need to check with MoPay API
        if (mopayTransactionId != null && mopayTransactionId.startsWith("POCHI") 
            && transaction.getTransactionType() == TransactionType.PAYMENT 
            && transaction.getStatus() == TransactionStatus.SUCCESS) {
            // NFC card payment - already successful, return current status
            return mapToPaymentResponse(transaction);
        }

        // For MOMO and Top-up transactions, extract actual MoPay transaction ID from message
        String actualMoPayTransactionId = mopayTransactionId;
        if (transaction.getMessage() != null && transaction.getMessage().contains("MOPAY_ID:")) {
            // Extract MoPay transaction ID from message
            String[] parts = transaction.getMessage().split("MOPAY_ID:");
            if (parts.length > 1) {
                actualMoPayTransactionId = parts[1].trim();
            }
        }
        
        // Check status with MoPay using the actual MoPay transaction ID
        MoPayResponse moPayResponse = moPayService.checkTransactionStatus(actualMoPayTransactionId);

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
                        Receiver topUpReceiver = transaction.getReceiver();
                        
                        if (topUpReceiver != null) {
                            // Merchant-specific top-up: add to merchant-specific balance
                            MerchantUserBalance merchantBalance = merchantUserBalanceRepository
                                    .findByUserIdAndReceiverId(user.getId(), topUpReceiver.getId())
                                    .orElse(null);
                            
                            if (merchantBalance == null) {
                                // Create new merchant-specific balance
                                merchantBalance = new MerchantUserBalance();
                                merchantBalance.setUser(user);
                                merchantBalance.setReceiver(topUpReceiver);
                                merchantBalance.setBalance(BigDecimal.ZERO);
                                merchantBalance.setTotalToppedUp(BigDecimal.ZERO);
                            }
                            
                            // Update merchant-specific balance
                            BigDecimal newMerchantBalance = merchantBalance.getBalance().add(transaction.getAmount());
                            merchantBalance.setBalance(newMerchantBalance);
                            merchantBalance.setTotalToppedUp(merchantBalance.getTotalToppedUp().add(transaction.getAmount()));
                            merchantUserBalanceRepository.save(merchantBalance);
                            
                            // Also update user's amountRemaining for merchant-specific top-ups
                            BigDecimal newAmountRemaining = user.getAmountRemaining().add(transaction.getAmount());
                            user.setAmountRemaining(newAmountRemaining);
                            
                            // Set balance after to user's amountRemaining (not merchant balance)
                            transaction.setBalanceAfter(newAmountRemaining);
                        } else {
                            // Global top-up: add to user's global balance
                        BigDecimal newBalance = user.getAmountRemaining().add(transaction.getAmount());
                        user.setAmountRemaining(newBalance);
                        user.setAmountOnCard(user.getAmountOnCard().add(transaction.getAmount()));
                        transaction.setBalanceAfter(newBalance);
                        }
                        
                        // Always update amountOnCard for tracking total topped up
                        user.setAmountOnCard(user.getAmountOnCard().add(transaction.getAmount()));
                        user.setLastTransactionDate(LocalDateTime.now());
                        userRepository.save(user);
                    } else if (transaction.getTransactionType() == TransactionType.PAYMENT && transaction.getMopayTransactionId() != null) {
                        // This is a MOMO PAYMENT - update receiver balance and user bonus
                        Receiver receiver = transaction.getReceiver();
                        if (receiver != null) {
                            // Each receiver (main merchant or submerchant) has its own separate balance
                            Receiver balanceOwner = receiver; // Always use receiver's own balance
                            
                            // Get the calculated values from transaction
                            BigDecimal paymentAmount = transaction.getAmount();
                            BigDecimal userBonusAmount = transaction.getUserBonusAmount() != null ? transaction.getUserBonusAmount() : BigDecimal.ZERO;
                            BigDecimal receiverBalanceAfter = transaction.getReceiverBalanceAfter();
                            
                            // Update receiver's own remaining balance (each receiver has separate balance)
                            // Ensure remaining balance never goes below 0
                            if (receiverBalanceAfter.compareTo(BigDecimal.ZERO) < 0) {
                                receiverBalanceAfter = BigDecimal.ZERO;
                                logger.info("Receiver balance would have been negative, setting to 0");
                            }
                            receiver.setRemainingBalance(receiverBalanceAfter);
                            receiver.setLastTransactionDate(LocalDateTime.now());
                            
                            // Update receiver's wallet balance and total received (each receiver has separate balance)
                            BigDecimal receiverNewWallet = receiver.getWalletBalance().add(paymentAmount);
                            BigDecimal receiverNewTotal = receiver.getTotalReceived().add(paymentAmount);
                            receiver.setWalletBalance(receiverNewWallet);
                            receiver.setTotalReceived(receiverNewTotal);
                            receiverRepository.save(receiver);
                            
                            logger.info("Updated receiver '{}' balance from MoPay callback: wallet={}, totalReceived={}, remainingBalance={}", 
                                receiver.getCompanyName(), receiverNewWallet, receiverNewTotal, receiverBalanceAfter);
                            
                            // Add user bonus to user's card balance if applicable (only if user exists)
                            User transactionUser = transaction.getUser();
                            if (transactionUser != null) {
                                if (userBonusAmount.compareTo(BigDecimal.ZERO) > 0) {
                                    BigDecimal userBalanceBefore = transactionUser.getAmountRemaining();
                                    BigDecimal userNewBalance = userBalanceBefore.add(userBonusAmount);
                                    // Ensure amountRemaining never goes below 0 (though bonus should make it positive)
                                    if (userNewBalance.compareTo(BigDecimal.ZERO) < 0) {
                                        userNewBalance = BigDecimal.ZERO;
                                    }
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
                
                // CRITICAL: Transaction ID must NEVER change - preserve the original POCHI transaction ID
                // Verify transaction ID hasn't changed (should never happen, but safety check)
                if (!originalTransactionId.equals(transaction.getMopayTransactionId())) {
                    logger.error("CRITICAL ERROR: Transaction ID changed from {} to {} - RESTORING ORIGINAL", 
                        originalTransactionId, transaction.getMopayTransactionId());
                    transaction.setMopayTransactionId(originalTransactionId);
                }
                
                transaction.setStatus(TransactionStatus.SUCCESS);
                logger.info("Transaction status updated to SUCCESS - Transaction ID remains: {}", transaction.getMopayTransactionId());
                
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
                            
                            // Send SMS and WhatsApp to receiver
                            if (receiver.getReceiverPhone() != null) {
                                String receiverPhoneNormalized = normalizePhoneTo12Digits(receiver.getReceiverPhone());
                                String receiverSmsMessage = String.format("Received %s RWF from %s via %s.", 
                                    paymentAmount.toPlainString(), payerName, paymentMethod);
                                String receiverWhatsAppMessage = String.format("[%s]: Paid %s RWF to [%s] via %s.",
                                    payerName, paymentAmount.toPlainString(), receiver.getCompanyName(), paymentMethod);
                                messagingService.sendSms(receiverSmsMessage, receiverPhoneNormalized);
                                whatsAppService.sendWhatsApp(receiverWhatsAppMessage, receiverPhoneNormalized);
                                logger.info("SMS and WhatsApp sent to receiver {} about payment (status: SUCCESS, method: {}): {}", 
                                    receiverPhoneNormalized, paymentMethod, receiverWhatsAppMessage);
                            }
                            
                            // Send SMS and WhatsApp to payer (user) if phone is available
                            if (payerPhone != null && !payerPhone.trim().isEmpty()) {
                                String payerPhoneNormalized = normalizePhoneTo12Digits(payerPhone);
                                String userSmsMessage;
                                String userWhatsAppMessage;
                                
                                // Include bonus amount in message if applicable
                                if (userBonusAmount.compareTo(BigDecimal.ZERO) > 0) {
                                    userSmsMessage = String.format("Payment of %s RWF to %s successful via %s. Bonus: %s RWF returned.",
                                        paymentAmount.toPlainString(), receiver.getCompanyName(), paymentMethod, userBonusAmount.toPlainString());
                                    userWhatsAppMessage = String.format("[%s]: Paid %s RWF to [%s] via %s. Bonus: %s RWF returned.",
                                        payerName, paymentAmount.toPlainString(), receiver.getCompanyName(), paymentMethod, userBonusAmount.toPlainString());
                                } else {
                                    userSmsMessage = String.format("Payment of %s RWF to %s successful via %s.",
                                        paymentAmount.toPlainString(), receiver.getCompanyName(), paymentMethod);
                                    userWhatsAppMessage = String.format("[%s]: Paid %s RWF to [%s] via %s.",
                                        payerName, paymentAmount.toPlainString(), receiver.getCompanyName(), paymentMethod);
                                }
                                
                                messagingService.sendSms(userSmsMessage, payerPhoneNormalized);
                                whatsAppService.sendWhatsApp(userWhatsAppMessage, payerPhoneNormalized);
                                logger.info("SMS and WhatsApp sent to payer {} about payment (status: SUCCESS, method: {}): {}", 
                                    payerPhoneNormalized, paymentMethod, userWhatsAppMessage);
                            } else {
                                logger.warn("Payer phone number is null or empty in transaction, skipping SMS/WhatsApp to payer");
                            }
                        } catch (Exception e) {
                            logger.error("Failed to send WhatsApp notifications for payment status check: ", e);
                            // Don't fail the transaction if WhatsApp fails
                        }
                    }
                }
            } else if ("FAILED".equalsIgnoreCase(mopayStatus) || "400".equals(mopayStatus) 
                || "500".equals(mopayStatus) || (moPayResponse.getSuccess() != null && !moPayResponse.getSuccess())) {
                // CRITICAL: Transaction ID must NEVER change - preserve the original POCHI transaction ID
                if (!originalTransactionId.equals(transaction.getMopayTransactionId())) {
                    logger.error("CRITICAL ERROR: Transaction ID changed from {} to {} - RESTORING ORIGINAL", 
                        originalTransactionId, transaction.getMopayTransactionId());
                    transaction.setMopayTransactionId(originalTransactionId);
                }
                
                transaction.setStatus(TransactionStatus.FAILED);
                if (moPayResponse.getMessage() != null) {
                    // Preserve MOPAY_ID in message if it exists, append new message
                    String existingMessage = transaction.getMessage();
                    if (existingMessage != null && existingMessage.contains("MOPAY_ID:")) {
                        // Extract and preserve MOPAY_ID
                        String[] parts = existingMessage.split("MOPAY_ID:");
                        String mopayIdPart = parts.length > 1 ? " | MOPAY_ID:" + parts[1].trim() : "";
                        transaction.setMessage(moPayResponse.getMessage() + mopayIdPart);
                    } else {
                    transaction.setMessage(moPayResponse.getMessage());
                }
            }
                logger.info("Transaction status updated to FAILED - Transaction ID remains: {}", transaction.getMopayTransactionId());
            }
            
            // Final safety check before saving - ensure transaction ID never changed
            if (!originalTransactionId.equals(transaction.getMopayTransactionId())) {
                logger.error("CRITICAL ERROR: Transaction ID changed before save from {} to {} - RESTORING ORIGINAL", 
                    originalTransactionId, transaction.getMopayTransactionId());
                transaction.setMopayTransactionId(originalTransactionId);
            }
            
            transactionRepository.save(transaction);
        }

        // Final verification in response
        PaymentResponse response = mapToPaymentResponse(transaction);
        if (!originalTransactionId.equals(response.getMopayTransactionId())) {
            logger.error("CRITICAL ERROR: Response transaction ID mismatch! Original: {}, Response: {}", 
                originalTransactionId, response.getMopayTransactionId());
            response.setMopayTransactionId(originalTransactionId);
        }
        
        return response;
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
    public Receiver getReceiverEntityById(UUID receiverId) {
        return receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found"));
    }

    @Transactional(readOnly = true)
    public ReceiverTransactionsResponse getTransactionsByReceiver(UUID receiverId, int page, int size, LocalDateTime fromDate, LocalDateTime toDate) {
        // Get current authenticated user
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        
        String currentUsername = authentication.getName();
        
        // Check if user is ADMIN - admins can access any receiver's transactions
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found"));
        
        // If not admin, verify the authenticated merchant can access this receiver's transactions
        if (!isAdmin) {
            // Check if authenticated user is a receiver/merchant
            Receiver authenticatedReceiver = receiverRepository.findByUsername(currentUsername)
                    .orElseThrow(() -> new RuntimeException("Merchant not found with username: " + currentUsername));
            
            // Allow access if:
            // 1. The authenticated merchant is requesting their own transactions
            // 2. The authenticated merchant is a main merchant and requesting a submerchant's transactions
            boolean canAccess = authenticatedReceiver.getId().equals(receiverId) ||
                               (authenticatedReceiver.getParentReceiver() == null && 
                                receiver.getParentReceiver() != null && 
                                receiver.getParentReceiver().getId().equals(authenticatedReceiver.getId()));
            
            if (!canAccess) {
                throw new RuntimeException("Access denied: You can only access your own transactions or your submerchants' transactions");
            }
        }
        
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

    /**
     * Get only PAYMENT transactions for a receiver (for PDF export)
     */
    @Transactional(readOnly = true)
    public ReceiverTransactionsResponse getPaymentTransactionsByReceiver(UUID receiverId, int page, int size, LocalDateTime fromDate, LocalDateTime toDate) {
        // Get current authenticated user
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        
        String currentUsername = authentication.getName();
        
        // Check if user is ADMIN - admins can access any receiver's transactions
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found"));
        
        // If not admin, verify the authenticated merchant can access this receiver's transactions
        if (!isAdmin) {
            // Check if authenticated user is a receiver/merchant
            Receiver authenticatedReceiver = receiverRepository.findByUsername(currentUsername)
                    .orElseThrow(() -> new RuntimeException("Merchant not found with username: " + currentUsername));
            
            // Allow access if:
            // 1. The authenticated merchant is requesting their own transactions
            // 2. The authenticated merchant is a main merchant and requesting a submerchant's transactions
            boolean canAccess = authenticatedReceiver.getId().equals(receiverId) ||
                               (authenticatedReceiver.getParentReceiver() == null && 
                                receiver.getParentReceiver() != null && 
                                receiver.getParentReceiver().getId().equals(authenticatedReceiver.getId()));
            
            if (!canAccess) {
                throw new RuntimeException("Access denied: You can only access your own transactions or your submerchants' transactions");
            }
        }
        
        // Build dynamic query using EntityManager - filter only PAYMENT transactions
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT t FROM Transaction t ");
        queryBuilder.append("LEFT JOIN FETCH t.user u ");
        queryBuilder.append("LEFT JOIN FETCH t.paymentCategory pc ");
        queryBuilder.append("LEFT JOIN FETCH t.receiver r ");
        queryBuilder.append("WHERE t.receiver.id = :receiverId ");
        queryBuilder.append("AND t.transactionType = :transactionType ");
        
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
        query.setParameter("transactionType", TransactionType.PAYMENT);
        
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
        countQueryBuilder.append("AND t.transactionType = :transactionType ");
        
        if (fromDate != null) {
            countQueryBuilder.append("AND t.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            countQueryBuilder.append("AND t.createdAt <= :toDate ");
        }
        
        Query countQuery = entityManager.createQuery(countQueryBuilder.toString(), Long.class);
        countQuery.setParameter("receiverId", receiverId);
        countQuery.setParameter("transactionType", TransactionType.PAYMENT);
        
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
        
        // Get ALL PAYMENT transactions for statistics calculation (without pagination)
        StringBuilder statsQueryBuilder = new StringBuilder();
        statsQueryBuilder.append("SELECT t FROM Transaction t ");
        statsQueryBuilder.append("LEFT JOIN FETCH t.user u ");
        statsQueryBuilder.append("WHERE t.receiver.id = :receiverId ");
        statsQueryBuilder.append("AND t.transactionType = :transactionType ");
        
        if (fromDate != null) {
            statsQueryBuilder.append("AND t.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            statsQueryBuilder.append("AND t.createdAt <= :toDate ");
        }
        
        Query statsQuery = entityManager.createQuery(statsQueryBuilder.toString(), Transaction.class);
        statsQuery.setParameter("receiverId", receiverId);
        statsQuery.setParameter("transactionType", TransactionType.PAYMENT);
        
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
        
        // Calculate statistics from ALL PAYMENT transactions (not just paginated)
        ReceiverTransactionsResponse.TransactionStatistics statistics = calculateReceiverTransactionStatistics(allTransactions);
        
        ReceiverTransactionsResponse response = new ReceiverTransactionsResponse();
        response.setTransactions(transactionResponses);
        response.setStatistics(statistics);
        
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
            // Count status for ALL transaction types (PAYMENT and TOP_UP)
            if (transaction.getStatus() == TransactionStatus.SUCCESS) {
                successfulTransactions++;
            } else if (transaction.getStatus() == TransactionStatus.PENDING) {
                pendingTransactions++;
            } else if (transaction.getStatus() == TransactionStatus.FAILED || transaction.getStatus() == TransactionStatus.CANCELLED) {
                failedTransactions++;
            }
            
            // Only calculate revenue and amounts for PAYMENT transactions
            if (transaction.getTransactionType() == TransactionType.PAYMENT) {
                // Count distinct users
                if (transaction.getUser() != null) {
                    distinctUsers.add(transaction.getUser().getId());
                }
                
                // Sum amounts for successful payment transactions only
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
        response.setTopUpType(transaction.getTopUpType()); // LOAN, MOMO, or CASH for TOP_UP transactions
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
        // MOMO payments: message contains "MOPAY_ID:" (MoPay transaction ID from MoPay API), phoneNumber is the payer's phone
        // NFC payments: message does NOT contain "MOPAY_ID:", phoneNumber is the receiver's phone
        // Both now have mopayTransactionId (POCHI format) for tracking purposes
        if (transaction.getTransactionType() == TransactionType.PAYMENT) {
            String message = transaction.getMessage() != null ? transaction.getMessage() : "";
            if (message.contains("MOPAY_ID:")) {
                // MOMO payment - message contains MoPay transaction ID from MoPay API
                response.setPayerPhone(transaction.getPhoneNumber());
                response.setPaymentMethod("MOMO");
            } else {
                // NFC Card payment - no MoPay transaction ID in message, internal transfer
                response.setPaymentMethod("NFC_CARD");
                // For NFC, phoneNumber field contains receiver phone (not payer), so we don't set payerPhone
            }
        } else if (transaction.getTransactionType() == TransactionType.TOP_UP) {
            // Top-up - typically MOMO, phoneNumber is the top-up source phone
            if (transaction.getPhoneNumber() != null && !transaction.getPhoneNumber().trim().isEmpty()) {
                response.setPayerPhone(transaction.getPhoneNumber());
            }
            response.setPaymentMethod("TOP_UP");
            
            // Add loan information if this is a LOAN top-up transaction
            if (transaction.getTopUpType() == TopUpType.LOAN) {
                Optional<Loan> loanOptional = loanRepository.findByTransactionId(transaction.getId());
                if (loanOptional.isPresent()) {
                    Loan loan = loanOptional.get();
                    response.setLoanId(loan.getId());
                    
                    LoanInfo loanInfo = new LoanInfo();
                    loanInfo.setLoanId(loan.getId());
                    loanInfo.setLoanAmount(loan.getLoanAmount());
                    loanInfo.setPaidAmount(loan.getPaidAmount());
                    loanInfo.setRemainingAmount(loan.getRemainingAmount());
                    loanInfo.setStatus(loan.getStatus());
                    loanInfo.setPaidAt(loan.getPaidAt());
                    loanInfo.setLastPaymentAt(loan.getLastPaymentAt());
                    response.setLoanInfo(loanInfo);
                }
            }
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

    /**
     * Generates a unique transaction ID starting with "POCHI"
     * Format: POCHI + timestamp (milliseconds) + random alphanumeric string
     * Example: POCHI1769123456789A3B2C1D
     */
    private String generateTransactionId() {
        long timestamp = System.currentTimeMillis();
        // Generate a random alphanumeric string (6 characters)
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder randomPart = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 6; i++) {
            randomPart.append(chars.charAt(random.nextInt(chars.length())));
        }
        return "POCHI" + timestamp + randomPart.toString();
    }

    /**
     * Calculate amountRemaining for a user.
     * In flexible mode: returns sum of balances from flexible merchants only.
     * Otherwise: returns the global amountRemaining from user entity.
     */
    /**
     * Calculate amountRemaining for a user.
     * Since we always add merchant balance amounts to user's global amountRemaining on top-up,
     * and always deduct from user's global amountRemaining on payment,
     * we simply return the user's global amountRemaining as the single source of truth.
     * 
     * Merchant balances are just records of what was topped up - they don't affect the actual balance.
     */
    private BigDecimal calculateAmountRemaining(User user) {
        // User's global amountRemaining is the single source of truth
        // It already includes all topped-up amounts from all merchants (LOAN, CASH, MOMO)
        return user.getAmountRemaining() != null ? user.getAmountRemaining() : BigDecimal.ZERO;
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
        
        // Calculate amountRemaining: in flexible mode, use sum of flexible merchant balances
        BigDecimal amountRemaining = calculateAmountRemaining(user);
        response.setAmountRemaining(amountRemaining);
        
        // Get merchant-specific balances
        List<MerchantUserBalance> merchantBalances = merchantUserBalanceRepository.findByUserId(user.getId());
        List<MerchantBalanceInfo> merchantBalanceInfos = merchantBalances.stream()
                .map(mb -> {
                    MerchantBalanceInfo info = new MerchantBalanceInfo();
                    info.setReceiverId(mb.getReceiver().getId());
                    info.setReceiverCompanyName(mb.getReceiver().getCompanyName());
                    info.setBalance(mb.getBalance());
                    info.setTotalToppedUp(mb.getTotalToppedUp());
                    return info;
                })
                .collect(java.util.stream.Collectors.toList());
        response.setMerchantBalances(merchantBalanceInfos);
        
        response.setStatus(user.getStatus());
        response.setLastTransactionDate(user.getLastTransactionDate());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }

    /**
     * Get all loans for a user
     */
    @Transactional(readOnly = true)
    public List<LoanResponse> getUserLoans(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        List<Loan> loans = loanRepository.findByUserId(userId);
        return loans.stream()
                .map(this::mapToLoanResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get all loans for a merchant/receiver
     */
    @Transactional(readOnly = true)
    public List<LoanResponse> getMerchantLoans(UUID receiverId) {
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found"));
        
        List<Loan> loans = loanRepository.findByReceiverId(receiverId);
        return loans.stream()
                .map(this::mapToLoanResponse)
                .collect(Collectors.toList());
    }

    /**
     * Pay back a loan
     */
    public LoanResponse payLoan(UUID userId, PayLoanRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Loan loan = loanRepository.findById(request.getLoanId())
                .orElseThrow(() -> new RuntimeException("Loan not found"));
        
        // Verify the loan belongs to this user
        if (!loan.getUser().getId().equals(userId)) {
            throw new RuntimeException("This loan does not belong to the specified user");
        }
        
        // Verify loan is not already fully paid
        if (loan.getStatus() == LoanStatus.COMPLETED) {
            throw new RuntimeException("This loan has already been fully paid");
        }
        
        // Verify payment amount doesn't exceed remaining amount
        if (request.getAmount().compareTo(loan.getRemainingAmount()) > 0) {
            throw new RuntimeException("Payment amount (" + request.getAmount() + 
                ") exceeds remaining loan amount (" + loan.getRemainingAmount() + ")");
        }
        
        // Check if user has sufficient balance (global or merchant-specific)
        BigDecimal merchantBalance = BigDecimal.ZERO;
        MerchantUserBalance merchantUserBalance = merchantUserBalanceRepository
                .findByUserIdAndReceiverId(userId, loan.getReceiver().getId())
                .orElse(null);
        
        if (merchantUserBalance != null) {
            merchantBalance = merchantUserBalance.getBalance();
        }
        
        BigDecimal globalBalance = user.getAmountRemaining();
        BigDecimal totalAvailableBalance = merchantBalance.add(globalBalance);
        
        if (totalAvailableBalance.compareTo(request.getAmount()) < 0) {
            throw new RuntimeException("Insufficient balance to pay loan. Available: " + totalAvailableBalance);
        }
        
        // Deduct payment from totalToppedUp (not from balance) in merchant-specific balance
        BigDecimal remainingPayment = request.getAmount();
        
        // Deduct from totalToppedUp in merchant-specific balance
        if (merchantUserBalance != null) {
            BigDecimal currentTotalToppedUp = merchantUserBalance.getTotalToppedUp();
            if (currentTotalToppedUp.compareTo(remainingPayment) >= 0) {
                BigDecimal newTotalToppedUp = currentTotalToppedUp.subtract(remainingPayment);
                merchantUserBalance.setTotalToppedUp(newTotalToppedUp);
                remainingPayment = BigDecimal.ZERO;
            } else {
                // If totalToppedUp is less than payment, use what's available and deduct rest from global balance
                merchantUserBalance.setTotalToppedUp(BigDecimal.ZERO);
                remainingPayment = remainingPayment.subtract(currentTotalToppedUp);
            }
            merchantUserBalanceRepository.save(merchantUserBalance);
        }
        
        // Then, deduct remaining amount from global balance
        if (remainingPayment.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal userNewBalance = globalBalance.subtract(remainingPayment);
            // Ensure amountRemaining never goes below 0
            if (userNewBalance.compareTo(BigDecimal.ZERO) < 0) {
                userNewBalance = BigDecimal.ZERO;
            }
            user.setAmountRemaining(userNewBalance);
            user.setLastTransactionDate(LocalDateTime.now());
            userRepository.save(user);
        }
        
        // Update loan
        BigDecimal newPaidAmount = loan.getPaidAmount().add(request.getAmount());
        BigDecimal newRemainingAmount = loan.getRemainingAmount().subtract(request.getAmount());
        
        loan.setPaidAmount(newPaidAmount);
        loan.setRemainingAmount(newRemainingAmount);
        loan.setLastPaymentAt(LocalDateTime.now());
        
        // Update loan status
        if (newRemainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            loan.setStatus(LoanStatus.COMPLETED);
            loan.setPaidAt(LocalDateTime.now());
        } else {
            loan.setStatus(LoanStatus.PARTIALLY_PAID);
        }
        
        Loan savedLoan = loanRepository.save(loan);
        
        // Create transaction record for loan payment
        Transaction paymentTransaction = new Transaction();
        paymentTransaction.setUser(user);
        paymentTransaction.setReceiver(loan.getReceiver());
        paymentTransaction.setTransactionType(TransactionType.PAYMENT);
        paymentTransaction.setAmount(request.getAmount());
        paymentTransaction.setPhoneNumber(user.getPhoneNumber());
        paymentTransaction.setMessage(request.getMessage() != null ? request.getMessage() : 
            "Loan repayment to " + loan.getReceiver().getCompanyName());
        paymentTransaction.setStatus(TransactionStatus.SUCCESS);
        paymentTransaction.setBalanceBefore(totalAvailableBalance);
        paymentTransaction.setBalanceAfter(totalAvailableBalance.subtract(request.getAmount()));
        
        String transactionId = generateTransactionId();
        paymentTransaction.setMopayTransactionId(transactionId);
        transactionRepository.save(paymentTransaction);
        
        return mapToLoanResponse(savedLoan);
    }

    /**
     * Update loan status and paid amount (admin/manual update)
     * Can be called by ADMIN or by the main merchant who gave the loan
     */
    public LoanResponse updateLoan(UpdateLoanRequest request) {
        Loan loan = loanRepository.findById(request.getLoanId())
                .orElseThrow(() -> new RuntimeException("Loan not found"));
        
        // Get current authenticated user
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        
        String currentUsername = authentication.getName();
        
        // Check if user is ADMIN - admins can update any loan
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        
        // If not admin, verify the merchant owns this loan
        if (!isAdmin) {
            // Get the receiver (merchant) who gave this loan
            Receiver loanReceiver = loan.getReceiver();
            
            // Check if the loan receiver is a submerchant - if so, get the main merchant
            Receiver mainMerchant = loanReceiver.getParentReceiver() != null 
                    ? loanReceiver.getParentReceiver() 
                    : loanReceiver;
            
            // Verify the authenticated user is the main merchant who owns this loan
            if (!mainMerchant.getUsername().equals(currentUsername)) {
                throw new RuntimeException("You can only update loans that you gave. This loan belongs to: " + 
                    loanReceiver.getCompanyName());
            }
        }
        
        // Validate paid amount doesn't exceed loan amount
        if (request.getPaidAmount().compareTo(loan.getLoanAmount()) > 0) {
            throw new RuntimeException("Paid amount (" + request.getPaidAmount() + 
                ") cannot exceed loan amount (" + loan.getLoanAmount() + ")");
        }
        
        // Calculate remaining amount
        BigDecimal newRemainingAmount = loan.getLoanAmount().subtract(request.getPaidAmount());
        
        // Update loan
        loan.setPaidAmount(request.getPaidAmount());
        loan.setRemainingAmount(newRemainingAmount);
        loan.setStatus(request.getStatus());
        
        // Update timestamps based on status
        if (request.getStatus() == LoanStatus.COMPLETED) {
            if (loan.getPaidAt() == null) {
                loan.setPaidAt(LocalDateTime.now());
            }
        }
        if (request.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            loan.setLastPaymentAt(LocalDateTime.now());
        }
        
        Loan savedLoan = loanRepository.save(loan);
        return mapToLoanResponse(savedLoan);
    }

    private LoanResponse mapToLoanResponse(Loan loan) {
        LoanResponse response = new LoanResponse();
        response.setId(loan.getId());
        response.setUserId(loan.getUser().getId());
        response.setUserFullNames(loan.getUser().getFullNames());
        response.setUserPhoneNumber(loan.getUser().getPhoneNumber());
        response.setReceiverId(loan.getReceiver().getId());
        response.setReceiverCompanyName(loan.getReceiver().getCompanyName());
        response.setTransactionId(loan.getTransaction().getId());
        response.setLoanAmount(loan.getLoanAmount());
        response.setPaidAmount(loan.getPaidAmount());
        response.setRemainingAmount(loan.getRemainingAmount());
        response.setStatus(loan.getStatus());
        response.setPaidAt(loan.getPaidAt());
        response.setLastPaymentAt(loan.getLastPaymentAt());
        response.setCreatedAt(loan.getCreatedAt());
        response.setUpdatedAt(loan.getUpdatedAt());
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


