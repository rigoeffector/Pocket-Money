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
import com.pocketmoney.pocketmoney.repository.PaymentCategoryRepository;
import com.pocketmoney.pocketmoney.repository.ReceiverRepository;
import com.pocketmoney.pocketmoney.repository.TransactionRepository;
import com.pocketmoney.pocketmoney.repository.UserRepository;
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

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final PaymentCategoryRepository paymentCategoryRepository;
    private final ReceiverRepository receiverRepository;
    private final MoPayService moPayService;
    private final PasswordEncoder passwordEncoder;

    public PaymentService(UserRepository userRepository, TransactionRepository transactionRepository,
                         PaymentCategoryRepository paymentCategoryRepository, ReceiverRepository receiverRepository,
                         MoPayService moPayService, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.paymentCategoryRepository = paymentCategoryRepository;
        this.receiverRepository = receiverRepository;
        this.moPayService = moPayService;
        this.passwordEncoder = passwordEncoder;
    }

    // Helper method to normalize phone number to 12 digits (250XXXXXXXXX format)
    private String normalizePhoneTo12Digits(String phone) {
        String cleaned = phone.replaceAll("[^0-9]", "");
        if (cleaned.startsWith("250") && cleaned.length() == 12) {
            return cleaned;
        } else if (cleaned.startsWith("0") && cleaned.length() == 10) {
            return "250" + cleaned.substring(1);
        } else if (cleaned.length() == 9) {
            return "250" + cleaned;
        } else if (cleaned.startsWith("250")) {
            return cleaned; // Already has country code, use as is
        } else {
            return "250" + cleaned; // Default: add country code
        }
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
        
        // Calculate discount and bonus amounts
        BigDecimal discountPercentage = receiver.getDiscountPercentage() != null ? receiver.getDiscountPercentage() : BigDecimal.ZERO;
        BigDecimal userBonusPercentage = receiver.getUserBonusPercentage() != null ? receiver.getUserBonusPercentage() : BigDecimal.ZERO;
        
        // Calculate discount amount (based on payment amount)
        BigDecimal discountAmount = paymentAmount.multiply(discountPercentage).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        
        // Calculate user bonus amount (based on payment amount)
        BigDecimal userBonusAmount = paymentAmount.multiply(userBonusPercentage).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        
        // Effective discount to receiver = discountAmount - userBonusAmount
        BigDecimal effectiveDiscount = discountAmount.subtract(userBonusAmount);
        
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

        // Credit receiver's wallet balance (full payment amount)
        BigDecimal receiverNewBalance = receiver.getWalletBalance().add(paymentAmount);
        BigDecimal receiverNewTotal = receiver.getTotalReceived().add(paymentAmount);
        receiver.setWalletBalance(receiverNewBalance);
        receiver.setTotalReceived(receiverNewTotal);
        
        // Update receiver's remaining balance
        // Receiver balance reduces by: paymentAmount - effectiveDiscount
        BigDecimal receiverBalanceBefore = receiver.getRemainingBalance() != null ? receiver.getRemainingBalance() : BigDecimal.ZERO;
        BigDecimal receiverBalanceReduction = paymentAmount.subtract(effectiveDiscount);
        BigDecimal receiverBalanceAfter = receiverBalanceBefore.subtract(receiverBalanceReduction);
        
        // Ensure receiver balance doesn't go below zero
        if (receiverBalanceAfter.compareTo(BigDecimal.ZERO) < 0) {
            receiverBalanceAfter = BigDecimal.ZERO;
        }
        
        receiver.setRemainingBalance(receiverBalanceAfter);
        receiver.setLastTransactionDate(LocalDateTime.now());
        receiverRepository.save(receiver);

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
        transaction.setReceiverBalanceBefore(receiverBalanceBefore);
        transaction.setReceiverBalanceAfter(receiverBalanceAfter);
        transaction.setStatus(TransactionStatus.SUCCESS); // Immediate success for internal transfers

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
                // Only update balance if transitioning from PENDING to SUCCESS
                if (transaction.getStatus() == TransactionStatus.PENDING) {
                    User user = transaction.getUser();
                    
                    if (transaction.getTransactionType() == TransactionType.TOP_UP) {
                        // Add to user balance for top-ups
                        BigDecimal newBalance = user.getAmountRemaining().add(transaction.getAmount());
                        user.setAmountRemaining(newBalance);
                        user.setAmountOnCard(user.getAmountOnCard().add(transaction.getAmount()));
                        transaction.setBalanceAfter(newBalance);
                    }
                    
                    user.setLastTransactionDate(LocalDateTime.now());
                    userRepository.save(user);
                }
                
                transaction.setStatus(TransactionStatus.SUCCESS);
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
    public List<PaymentResponse> getAllTransactions() {
        return transactionRepository.findAllWithUser()
                .stream()
                .map(this::mapToPaymentResponse)
                .collect(Collectors.toList());
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
    public List<PaymentResponse> getTransactionsByReceiver(UUID receiverId) {
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found"));
        return transactionRepository.findByReceiverOrderByCreatedAtDescWithUser(receiver)
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
        response.setUserId(transaction.getUser().getId());
        response.setUserFullNames(transaction.getUser().getFullNames());
        
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
        PaymentResponse response = new PaymentResponse();
        response.setId(transaction.getId());
        response.setUserId(transaction.getUser().getId());
        response.setUser(mapToUserResponse(transaction.getUser())); // Include full user information
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
        response.setReceiverBalanceBefore(transaction.getReceiverBalanceBefore());
        response.setReceiverBalanceAfter(transaction.getReceiverBalanceAfter());
        response.setCreatedAt(transaction.getCreatedAt());
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
}

