package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.*;
import com.pocketmoney.pocketmoney.entity.PaymentCategory;
import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import com.pocketmoney.pocketmoney.entity.Transaction;
import com.pocketmoney.pocketmoney.entity.TransactionStatus;
import com.pocketmoney.pocketmoney.entity.TransactionType;
import com.pocketmoney.pocketmoney.entity.User;
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

    public PaymentResponse topUp(UUID userId, TopUpRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Create MoPay initiate request
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        moPayRequest.setAmount(request.getAmount());
        moPayRequest.setCurrency("RWF");
        // Normalize phone to 12 digits and convert to Long (MoPay API requires 12 digits)
        String normalizedPayerPhone = normalizePhoneTo12Digits(request.getPhone());
        moPayRequest.setPhone(Long.parseLong(normalizedPayerPhone));
        moPayRequest.setPayment_mode("MOBILE");
        moPayRequest.setMessage(request.getMessage() != null ? request.getMessage() : "Top up to pocket money card");

        // Create transfer to user's card (receiver)
        MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
        transfer.setAmount(request.getAmount());
        // Normalize user phone to 12 digits (MoPay API requires 12 digits)
        String normalizedUserPhone = normalizePhoneTo12Digits(user.getPhoneNumber());
        transfer.setPhone(Long.parseLong(normalizedUserPhone));
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
        if (user.getAmountRemaining().compareTo(request.getAmount()) < 0) {
            throw new RuntimeException("Insufficient balance. Available: " + user.getAmountRemaining());
        }

        // For PAYMENT: Direct internal transfer (no MoPay integration needed)
        // Deduct from user balance
        BigDecimal userNewBalance = user.getAmountRemaining().subtract(request.getAmount());
        user.setAmountRemaining(userNewBalance);
        user.setLastTransactionDate(LocalDateTime.now());
        userRepository.save(user);

        // Credit receiver's wallet
        BigDecimal receiverNewBalance = receiver.getWalletBalance().add(request.getAmount());
        BigDecimal receiverNewTotal = receiver.getTotalReceived().add(request.getAmount());
        receiver.setWalletBalance(receiverNewBalance);
        receiver.setTotalReceived(receiverNewTotal);
        receiver.setLastTransactionDate(LocalDateTime.now());
        receiverRepository.save(receiver);

        // Create transaction record - PAYMENT is immediate (no MoPay, internal transfer)
        Transaction transaction = new Transaction();
        transaction.setUser(user);
        transaction.setPaymentCategory(paymentCategory);
        transaction.setReceiver(receiver);
        transaction.setTransactionType(TransactionType.PAYMENT);
        transaction.setAmount(request.getAmount());
        transaction.setPhoneNumber(receiver.getReceiverPhone());
        transaction.setMessage(request.getMessage() != null ? request.getMessage() : "Payment from pocket money card");
        transaction.setBalanceBefore(user.getAmountRemaining().add(request.getAmount())); // Balance before deduction
        transaction.setBalanceAfter(userNewBalance);
        transaction.setStatus(TransactionStatus.SUCCESS); // Immediate success for internal transfers

        Transaction savedTransaction = transactionRepository.save(transaction);
        return mapToPaymentResponse(savedTransaction);
    }

    @Transactional(readOnly = true)
    public BalanceResponse checkBalance(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        BalanceResponse response = new BalanceResponse();
        response.setUserId(user.getId());
        response.setAmountOnCard(user.getAmountOnCard());
        response.setAmountRemaining(user.getAmountRemaining());
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

    private PaymentResponse mapToPaymentResponse(Transaction transaction) {
        PaymentResponse response = new PaymentResponse();
        response.setId(transaction.getId());
        response.setUserId(transaction.getUser().getId());
        response.setUser(mapToUserResponse(transaction.getUser())); // Include full user information
        response.setTransactionType(transaction.getTransactionType());
        response.setAmount(transaction.getAmount());
        response.setMopayTransactionId(transaction.getMopayTransactionId());
        response.setStatus(transaction.getStatus());
        response.setBalanceBefore(transaction.getBalanceBefore());
        response.setBalanceAfter(transaction.getBalanceAfter());
        response.setCreatedAt(transaction.getCreatedAt());
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

