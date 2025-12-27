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
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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
    private final EntityManager entityManager;

    public PaymentService(UserRepository userRepository, TransactionRepository transactionRepository,
                         PaymentCategoryRepository paymentCategoryRepository, ReceiverRepository receiverRepository,
                         MoPayService moPayService, PasswordEncoder passwordEncoder, EntityManager entityManager) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.paymentCategoryRepository = paymentCategoryRepository;
        this.receiverRepository = receiverRepository;
        this.moPayService = moPayService;
        this.passwordEncoder = passwordEncoder;
        this.entityManager = entityManager;
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
            throw new RuntimeException("Insufficient Remaining Balance. Please Contact BeFosot Administrator");
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
        response.setAdminIncomeAmount(transaction.getAdminIncomeAmount());
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

    @Transactional(readOnly = true)
    public AdminIncomeResponse getAdminIncome(LocalDateTime fromDate, LocalDateTime toDate, UUID receiverId) {
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

        // Get detailed transaction list
        List<AdminIncomeResponse.AdminIncomeTransaction> transactions = getAdminIncomeTransactionList(fromDate, toDate, receiverId);

        AdminIncomeResponse response = new AdminIncomeResponse();
        response.setTotalIncome(totalIncome);
        response.setTotalTransactions(totalTransactions);
        response.setFromDate(fromDate);
        response.setToDate(toDate);
        response.setBreakdown(breakdown);
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

    private List<AdminIncomeResponse.AdminIncomeTransaction> getAdminIncomeTransactionList(LocalDateTime fromDate, LocalDateTime toDate, UUID receiverId) {
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

        // Set description based on transaction type
        if (transaction.getTransactionType() == TransactionType.PAYMENT) {
            activity.setDescription("Payment to " + 
                (transaction.getReceiver() != null ? transaction.getReceiver().getCompanyName() : "Unknown"));
            activity.setUserName(transaction.getUser().getFullNames());
            if (transaction.getReceiver() != null) {
                activity.setReceiverName(transaction.getReceiver().getCompanyName());
            }
            if (transaction.getPaymentCategory() != null) {
                activity.setPaymentCategoryName(transaction.getPaymentCategory().getName());
            }
        } else if (transaction.getTransactionType() == TransactionType.TOP_UP) {
            activity.setDescription("Top-up for " + transaction.getUser().getFullNames());
            activity.setUserName(transaction.getUser().getFullNames());
        } else {
            activity.setDescription(transaction.getMessage() != null ? transaction.getMessage() : 
                transaction.getTransactionType().name());
            activity.setUserName(transaction.getUser().getFullNames());
        }

        return activity;
    }
}

