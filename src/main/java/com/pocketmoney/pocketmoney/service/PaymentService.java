package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.*;
import com.pocketmoney.pocketmoney.entity.Transaction;
import com.pocketmoney.pocketmoney.entity.TransactionStatus;
import com.pocketmoney.pocketmoney.entity.TransactionType;
import com.pocketmoney.pocketmoney.entity.User;
import com.pocketmoney.pocketmoney.repository.TransactionRepository;
import com.pocketmoney.pocketmoney.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Transactional
public class PaymentService {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final MoPayService moPayService;
    private final PasswordEncoder passwordEncoder;

    public PaymentService(UserRepository userRepository, TransactionRepository transactionRepository,
                         MoPayService moPayService, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.moPayService = moPayService;
        this.passwordEncoder = passwordEncoder;
    }

    public PaymentResponse topUp(UUID userId, TopUpRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Create MoPay initiate request
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        moPayRequest.setAmount(request.getAmount());
        moPayRequest.setCurrency("RWF");
        moPayRequest.setPhone(request.getPhone());
        moPayRequest.setPayment_mode("MOBILE");
        moPayRequest.setMessage(request.getMessage() != null ? request.getMessage() : "Top up to pocket money card");

        // Create transfer to user's card (receiver)
        MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
        transfer.setAmount(request.getAmount());
        transfer.setPhone(user.getPhoneNumber()); // Receiver: user's phone number
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
        transaction.setStatus(TransactionStatus.PENDING);

        if (moPayResponse != null && moPayResponse.isSuccess() && moPayResponse.getTransaction_id() != null) {
            transaction.setMopayTransactionId(moPayResponse.getTransaction_id());
            
            // Update user balance
            BigDecimal newBalance = user.getAmountRemaining().add(request.getAmount());
            user.setAmountRemaining(newBalance);
            user.setAmountOnCard(user.getAmountOnCard().add(request.getAmount()));
            user.setLastTransactionDate(LocalDateTime.now());
            userRepository.save(user);

            transaction.setBalanceAfter(newBalance);
            transaction.setStatus(TransactionStatus.SUCCESS);
        } else {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setMessage(moPayResponse != null ? moPayResponse.getMessage() : "Payment initiation failed");
        }

        Transaction savedTransaction = transactionRepository.save(transaction);
        return mapToPaymentResponse(savedTransaction);
    }

    public PaymentResponse makePayment(UUID userId, PaymentRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Verify PIN
        if (!passwordEncoder.matches(request.getPin(), user.getPin())) {
            throw new RuntimeException("Invalid PIN");
        }

        // Check balance
        if (user.getAmountRemaining().compareTo(request.getAmount()) < 0) {
            throw new RuntimeException("Insufficient balance. Available: " + user.getAmountRemaining());
        }

        // Create MoPay initiate request
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        moPayRequest.setAmount(request.getAmount());
        moPayRequest.setCurrency("RWF");
        moPayRequest.setPhone(user.getPhoneNumber());
        moPayRequest.setPayment_mode("MOBILE");
        moPayRequest.setMessage(request.getMessage() != null ? request.getMessage() : "Payment from pocket money card");

        // Create transfer to receiver
        MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
        transfer.setAmount(request.getAmount());
        transfer.setPhone(request.getReceiverPhone());
        transfer.setMessage(request.getMessage() != null ? request.getMessage() : "Payment from pocket money");
        moPayRequest.setTransfers(java.util.List.of(transfer));

        // Initiate payment with MoPay
        MoPayResponse moPayResponse = moPayService.initiatePayment(moPayRequest);

        // Create transaction record
        Transaction transaction = new Transaction();
        transaction.setUser(user);
        transaction.setTransactionType(TransactionType.PAYMENT);
        transaction.setAmount(request.getAmount());
        transaction.setPhoneNumber(request.getReceiverPhone());
        transaction.setMessage(moPayRequest.getMessage());
        transaction.setBalanceBefore(user.getAmountRemaining());
        transaction.setStatus(TransactionStatus.PENDING);

        if (moPayResponse != null && moPayResponse.isSuccess() && moPayResponse.getTransaction_id() != null) {
            transaction.setMopayTransactionId(moPayResponse.getTransaction_id());
            
            // Deduct from user balance
            BigDecimal newBalance = user.getAmountRemaining().subtract(request.getAmount());
            user.setAmountRemaining(newBalance);
            user.setLastTransactionDate(LocalDateTime.now());
            userRepository.save(user);

            transaction.setBalanceAfter(newBalance);
            transaction.setStatus(TransactionStatus.SUCCESS);
        } else {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setMessage(moPayResponse != null ? moPayResponse.getMessage() : "Payment initiation failed");
        }

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
        Transaction transaction = transactionRepository.findByMopayTransactionId(mopayTransactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        // Check status with MoPay
        MoPayResponse moPayResponse = moPayService.checkTransactionStatus(mopayTransactionId);

        if (moPayResponse != null && moPayResponse.isSuccess()) {
            // Update transaction status based on MoPay response
            if ("SUCCESS".equalsIgnoreCase(moPayResponse.getStatus())) {
                transaction.setStatus(TransactionStatus.SUCCESS);
            } else if ("FAILED".equalsIgnoreCase(moPayResponse.getStatus())) {
                transaction.setStatus(TransactionStatus.FAILED);
            }
            transactionRepository.save(transaction);
        }

        return mapToPaymentResponse(transaction);
    }

    private PaymentResponse mapToPaymentResponse(Transaction transaction) {
        PaymentResponse response = new PaymentResponse();
        response.setId(transaction.getId());
        response.setUserId(transaction.getUser().getId());
        response.setTransactionType(transaction.getTransactionType());
        response.setAmount(transaction.getAmount());
        response.setMopayTransactionId(transaction.getMopayTransactionId());
        response.setStatus(transaction.getStatus());
        response.setBalanceBefore(transaction.getBalanceBefore());
        response.setBalanceAfter(transaction.getBalanceAfter());
        response.setCreatedAt(transaction.getCreatedAt());
        return response;
    }
}

