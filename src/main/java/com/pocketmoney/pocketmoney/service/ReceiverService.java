package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.AssignBalanceRequest;
import com.pocketmoney.pocketmoney.dto.BalanceAssignmentHistoryResponse;
import com.pocketmoney.pocketmoney.dto.CreateReceiverRequest;
import com.pocketmoney.pocketmoney.dto.PaginatedResponse;
import com.pocketmoney.pocketmoney.dto.CreateSubmerchantRequest;
import com.pocketmoney.pocketmoney.dto.MoPayInitiateRequest;
import com.pocketmoney.pocketmoney.dto.MoPayResponse;
import com.pocketmoney.pocketmoney.entity.BalanceAssignmentStatus;
import com.pocketmoney.pocketmoney.dto.ReceiverAnalyticsResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverDashboardResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverLoginResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverWalletResponse;
import com.pocketmoney.pocketmoney.dto.UpdateReceiverRequest;
import com.pocketmoney.pocketmoney.entity.Transaction;
import com.pocketmoney.pocketmoney.entity.BalanceAssignmentHistory;
import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import com.pocketmoney.pocketmoney.repository.BalanceAssignmentHistoryRepository;
import com.pocketmoney.pocketmoney.repository.ReceiverRepository;
import com.pocketmoney.pocketmoney.repository.TransactionRepository;
import com.pocketmoney.pocketmoney.util.JwtUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ReceiverService {

    private final ReceiverRepository receiverRepository;
    private final TransactionRepository transactionRepository;
    private final BalanceAssignmentHistoryRepository balanceAssignmentHistoryRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final MoPayService moPayService;
    private final EntityManager entityManager;

    public ReceiverService(ReceiverRepository receiverRepository, TransactionRepository transactionRepository,
                          BalanceAssignmentHistoryRepository balanceAssignmentHistoryRepository,
                          PasswordEncoder passwordEncoder, JwtUtil jwtUtil, MoPayService moPayService,
                          EntityManager entityManager) {
        this.receiverRepository = receiverRepository;
        this.transactionRepository = transactionRepository;
        this.balanceAssignmentHistoryRepository = balanceAssignmentHistoryRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.moPayService = moPayService;
        this.entityManager = entityManager;
    }

    public ReceiverResponse createReceiver(CreateReceiverRequest request) {
        // Check if username already exists
        if (receiverRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already exists: " + request.getUsername());
        }

        // Check if phone number already exists
        if (receiverRepository.existsByReceiverPhone(request.getReceiverPhone())) {
            throw new RuntimeException("Receiver phone number already exists: " + request.getReceiverPhone());
        }

        // Normalize email: convert empty/blank strings to null
        String email = (request.getEmail() != null && !request.getEmail().trim().isEmpty()) 
                ? request.getEmail().trim() 
                : null;

        // Validate email format if provided
        if (email != null) {
            String emailRegex = "^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$";
            if (!email.matches(emailRegex)) {
                throw new RuntimeException("Invalid email format");
            }
            // Check if email already exists
            if (receiverRepository.existsByEmail(email)) {
                throw new RuntimeException("Email already exists: " + email);
            }
        }

        Receiver receiver = new Receiver();
        receiver.setCompanyName(request.getCompanyName());
        receiver.setManagerName(request.getManagerName());
        receiver.setUsername(request.getUsername());
        receiver.setPassword(passwordEncoder.encode(request.getPassword())); // Hash the password
        receiver.setReceiverPhone(request.getReceiverPhone());
        receiver.setAccountNumber(request.getAccountNumber());
        receiver.setStatus(request.getStatus() != null ? request.getStatus() : ReceiverStatus.NOT_ACTIVE);
        receiver.setEmail(email);
        receiver.setAddress(request.getAddress());
        receiver.setDescription(request.getDescription());

        Receiver savedReceiver = receiverRepository.save(receiver);
        return mapToResponse(savedReceiver);
    }

    @Transactional(readOnly = true)
    public ReceiverResponse getReceiverById(UUID id) {
        Receiver receiver = receiverRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + id));
        ReceiverResponse response = mapToResponse(receiver);
        
        // Count pending balance assignments
        List<BalanceAssignmentHistory> pendingAssignments = balanceAssignmentHistoryRepository.findPendingByReceiverId(id);
        response.setPendingBalanceAssignments(pendingAssignments.size());
        
        return response;
    }

    @Transactional(readOnly = true)
    public ReceiverResponse getReceiverByPhone(String phone) {
        Receiver receiver = receiverRepository.findByReceiverPhone(phone)
                .orElseThrow(() -> new RuntimeException("Receiver not found with phone: " + phone));
        return mapToResponse(receiver);
    }

    @Transactional(readOnly = true)
    public List<ReceiverResponse> getAllReceivers() {
        return receiverRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ReceiverResponse> getActiveReceivers() {
        return receiverRepository.findByStatus(ReceiverStatus.ACTIVE)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ReceiverResponse> getReceiversByStatus(ReceiverStatus status) {
        return receiverRepository.findByStatus(status)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public ReceiverResponse updateReceiver(UUID id, UpdateReceiverRequest request) {
        Receiver receiver = receiverRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + id));

        // Check if username is being changed and if it conflicts
        if (request.getUsername() != null && !receiver.getUsername().equals(request.getUsername())) {
            if (receiverRepository.existsByUsername(request.getUsername())) {
                throw new RuntimeException("Username already exists: " + request.getUsername());
            }
            receiver.setUsername(request.getUsername());
        }

        // Check if phone is being changed and if it conflicts
        if (request.getReceiverPhone() != null && !receiver.getReceiverPhone().equals(request.getReceiverPhone())) {
            if (receiverRepository.existsByReceiverPhone(request.getReceiverPhone())) {
                throw new RuntimeException("Receiver phone number already exists: " + request.getReceiverPhone());
            }
            receiver.setReceiverPhone(request.getReceiverPhone());
        }

        // Update password if provided
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            receiver.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        // Update other fields
        if (request.getCompanyName() != null) {
            receiver.setCompanyName(request.getCompanyName());
        }
        if (request.getManagerName() != null) {
            receiver.setManagerName(request.getManagerName());
        }
        if (request.getAccountNumber() != null) {
            receiver.setAccountNumber(request.getAccountNumber());
        }
        if (request.getStatus() != null) {
            receiver.setStatus(request.getStatus());
        }
        if (request.getEmail() != null) {
            receiver.setEmail(request.getEmail());
        }
        if (request.getAddress() != null) {
            receiver.setAddress(request.getAddress());
        }
        if (request.getDescription() != null) {
            receiver.setDescription(request.getDescription());
        }

        // Update balance and discount settings
        if (request.getAssignedBalance() != null) {
            BigDecimal newAssignedBalance = request.getAssignedBalance();
            BigDecimal currentAssigned = receiver.getAssignedBalance();
            
            // Track balance assignment in history (only if balance actually changes)
            if (currentAssigned.compareTo(newAssignedBalance) != 0) {
                BigDecimal balanceDifference = newAssignedBalance.subtract(currentAssigned);
                
                // If balance is being increased, initiate MoPay payment
                if (balanceDifference.compareTo(BigDecimal.ZERO) > 0) {
                    // Validate admin phone is provided
                    if (request.getAdminPhone() == null || request.getAdminPhone().trim().isEmpty()) {
                        throw new RuntimeException("Admin phone number is required for balance assignment");
                    }
                    
                    // Initiate MoPay payment from admin to receiver
                    MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
                    moPayRequest.setAmount(balanceDifference);
                    moPayRequest.setCurrency("RWF");
                    
                    // Normalize admin phone to 12 digits
                    String normalizedAdminPhone = normalizePhoneTo12Digits(request.getAdminPhone());
                    moPayRequest.setPhone(Long.parseLong(normalizedAdminPhone));
                    moPayRequest.setPayment_mode("MOBILE");
                    moPayRequest.setMessage("Balance assignment to " + receiver.getCompanyName());
                    
                    // Create transfer to receiver
                    MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
                    transfer.setAmount(balanceDifference);
                    // Normalize receiver phone to 12 digits
                    String normalizedReceiverPhone = normalizePhoneTo12Digits(receiver.getReceiverPhone());
                    transfer.setPhone(Long.parseLong(normalizedReceiverPhone));
                    transfer.setMessage("Balance assignment from admin");
                    moPayRequest.setTransfers(java.util.List.of(transfer));
                    
                    // Initiate payment with MoPay
                    MoPayResponse moPayResponse = moPayService.initiatePayment(moPayRequest);
                    
                    // Create balance assignment history
                    BalanceAssignmentHistory history = new BalanceAssignmentHistory();
                    history.setReceiver(receiver);
                    history.setAssignedBalance(newAssignedBalance);
                    history.setPreviousAssignedBalance(currentAssigned);
                    history.setBalanceDifference(balanceDifference);
                    history.setAssignedBy("ADMIN"); // TODO: Get from authentication context
                    history.setNotes("Balance assignment via MoPay payment");
                    history.setStatus(BalanceAssignmentStatus.PENDING);
                    
                    // Check MoPay response
                    String transactionId = moPayResponse != null ? moPayResponse.getTransactionId() : null;
                    if (moPayResponse != null && moPayResponse.getStatus() != null && moPayResponse.getStatus() == 201 
                        && transactionId != null) {
                        // Successfully initiated - store transaction ID
                        history.setMopayTransactionId(transactionId);
                    } else {
                        // Initiation failed
                        history.setStatus(BalanceAssignmentStatus.REJECTED);
                        String errorMessage = moPayResponse != null && moPayResponse.getMessage() != null 
                            ? moPayResponse.getMessage() 
                            : "MoPay payment initiation failed";
                        history.setNotes("Balance assignment failed: " + errorMessage);
                    }
                    
                    balanceAssignmentHistoryRepository.save(history);
                } else {
                    // Balance is being decreased - just create history entry (no payment needed)
                    BalanceAssignmentHistory history = new BalanceAssignmentHistory();
                    history.setReceiver(receiver);
                    history.setAssignedBalance(newAssignedBalance);
                    history.setPreviousAssignedBalance(currentAssigned);
                    history.setBalanceDifference(balanceDifference);
                    history.setAssignedBy("ADMIN");
                    history.setNotes("Balance assignment updated (reduction)");
                    history.setStatus(BalanceAssignmentStatus.PENDING);
                    balanceAssignmentHistoryRepository.save(history);
                }
                
                // Don't update receiver balance until approved - wait for receiver approval
            }
            // If balance hasn't changed, no need to create history entry
            // But we still allow updating other fields
        }

        if (request.getDiscountPercentage() != null) {
            // Validate discount percentage is between 0 and 100
            if (request.getDiscountPercentage().compareTo(BigDecimal.ZERO) < 0 ||
                request.getDiscountPercentage().compareTo(new BigDecimal("100")) > 0) {
                throw new RuntimeException("Discount percentage must be between 0 and 100");
            }
            receiver.setDiscountPercentage(request.getDiscountPercentage());
        }

        if (request.getUserBonusPercentage() != null) {
            // Validate user bonus percentage is between 0 and 100
            if (request.getUserBonusPercentage().compareTo(BigDecimal.ZERO) < 0 ||
                request.getUserBonusPercentage().compareTo(new BigDecimal("100")) > 0) {
                throw new RuntimeException("User bonus percentage must be between 0 and 100");
            }
            receiver.setUserBonusPercentage(request.getUserBonusPercentage());
        }

        Receiver updatedReceiver = receiverRepository.save(receiver);
        return mapToResponse(updatedReceiver);
    }

    public BalanceAssignmentHistoryResponse assignBalance(UUID receiverId, AssignBalanceRequest request) {
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + receiverId));

        BigDecimal newAssignedBalance = request.getAssignedBalance();
        BigDecimal currentAssigned = receiver.getAssignedBalance();
        BigDecimal balanceDifference = newAssignedBalance.subtract(currentAssigned);

        // Validate receiver phone matches
        String normalizedReceiverPhone = normalizePhoneTo12Digits(receiver.getReceiverPhone());
        String normalizedRequestReceiverPhone = normalizePhoneTo12Digits(request.getReceiverPhone());
        if (!normalizedReceiverPhone.equals(normalizedRequestReceiverPhone)) {
            throw new RuntimeException("Receiver phone number does not match. Expected: " + receiver.getReceiverPhone() + ", Provided: " + request.getReceiverPhone());
        }

        // Allow multiple assignment attempts - each call creates a new history entry
        // This allows retries if payment fails

        // If reducing balance (difference is negative or zero), create history entry but no payment
        if (balanceDifference.compareTo(BigDecimal.ZERO) <= 0) {
            // Balance is being reduced - just create history entry (no payment needed)
            BalanceAssignmentHistory history = new BalanceAssignmentHistory();
            history.setReceiver(receiver);
            history.setAssignedBalance(newAssignedBalance);
            history.setPreviousAssignedBalance(currentAssigned);
            history.setBalanceDifference(balanceDifference);
            history.setAssignedBy("ADMIN");
            history.setNotes(request.getNotes() != null ? request.getNotes() : "Balance reduction");
            history.setStatus(BalanceAssignmentStatus.PENDING); // Still requires approval for reductions
            
            // Update discount and bonus percentages if provided
            if (request.getDiscountPercentage() != null) {
                if (request.getDiscountPercentage().compareTo(BigDecimal.ZERO) < 0 ||
                    request.getDiscountPercentage().compareTo(new BigDecimal("100")) > 0) {
                    throw new RuntimeException("Discount percentage must be between 0 and 100");
                }
                receiver.setDiscountPercentage(request.getDiscountPercentage());
            }

            if (request.getUserBonusPercentage() != null) {
                if (request.getUserBonusPercentage().compareTo(BigDecimal.ZERO) < 0 ||
                    request.getUserBonusPercentage().compareTo(new BigDecimal("100")) > 0) {
                    throw new RuntimeException("User bonus percentage must be between 0 and 100");
                }
                receiver.setUserBonusPercentage(request.getUserBonusPercentage());
            }
            
            receiverRepository.save(receiver);
            BalanceAssignmentHistory savedHistory = balanceAssignmentHistoryRepository.save(history);
            return mapToBalanceAssignmentHistoryResponse(savedHistory);
        }

        // Balance is being increased - initiate MoPay payment
        // Update discount and bonus percentages if provided
        if (request.getDiscountPercentage() != null) {
            if (request.getDiscountPercentage().compareTo(BigDecimal.ZERO) < 0 ||
                request.getDiscountPercentage().compareTo(new BigDecimal("100")) > 0) {
                throw new RuntimeException("Discount percentage must be between 0 and 100");
            }
            receiver.setDiscountPercentage(request.getDiscountPercentage());
        }

        if (request.getUserBonusPercentage() != null) {
            if (request.getUserBonusPercentage().compareTo(BigDecimal.ZERO) < 0 ||
                request.getUserBonusPercentage().compareTo(new BigDecimal("100")) > 0) {
                throw new RuntimeException("User bonus percentage must be between 0 and 100");
            }
            receiver.setUserBonusPercentage(request.getUserBonusPercentage());
        }

        // Initiate MoPay payment from admin to receiver
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        moPayRequest.setAmount(balanceDifference);
        moPayRequest.setCurrency("RWF");
        
        // Normalize admin phone to 12 digits
        String normalizedAdminPhone = normalizePhoneTo12Digits(request.getAdminPhone());
        moPayRequest.setPhone(Long.parseLong(normalizedAdminPhone));
        moPayRequest.setPayment_mode("MOBILE");
        moPayRequest.setMessage("Balance assignment to " + receiver.getCompanyName());
        
        // Create transfer to receiver
        MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
        transfer.setAmount(balanceDifference);
        transfer.setPhone(Long.parseLong(normalizedReceiverPhone));
        transfer.setMessage("Balance assignment from admin");
        moPayRequest.setTransfers(java.util.List.of(transfer));
        
        // Initiate payment with MoPay
        MoPayResponse moPayResponse = moPayService.initiatePayment(moPayRequest);
        
        // Create balance assignment history
        BalanceAssignmentHistory history = new BalanceAssignmentHistory();
        history.setReceiver(receiver);
        history.setAssignedBalance(newAssignedBalance);
        history.setPreviousAssignedBalance(currentAssigned);
        history.setBalanceDifference(balanceDifference);
        history.setAssignedBy("ADMIN"); // TODO: Get from authentication context
        history.setNotes(request.getNotes() != null ? request.getNotes() : "Balance assignment via MoPay payment");
        history.setStatus(BalanceAssignmentStatus.PENDING);
        
        // Check MoPay response
        String transactionId = moPayResponse != null ? moPayResponse.getTransactionId() : null;
        if (moPayResponse != null && moPayResponse.getStatus() != null && moPayResponse.getStatus() == 201 
            && transactionId != null) {
            // Successfully initiated - store transaction ID
            history.setMopayTransactionId(transactionId);
        } else {
            // Initiation failed
            history.setStatus(BalanceAssignmentStatus.REJECTED);
            String errorMessage = moPayResponse != null && moPayResponse.getMessage() != null 
                ? moPayResponse.getMessage() 
                : "MoPay payment initiation failed";
            history.setNotes("Balance assignment failed: " + errorMessage);
        }
        
        // Save receiver updates (discount/bonus percentages)
        receiverRepository.save(receiver);
        
        // Save balance assignment history
        BalanceAssignmentHistory savedHistory = balanceAssignmentHistoryRepository.save(history);
        return mapToBalanceAssignmentHistoryResponse(savedHistory);
    }

    public ReceiverResponse suspendReceiver(UUID id) {
        Receiver receiver = receiverRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + id));
        receiver.setStatus(ReceiverStatus.SUSPENDED);
        Receiver updatedReceiver = receiverRepository.save(receiver);
        return mapToResponse(updatedReceiver);
    }

    public ReceiverResponse activateReceiver(UUID id) {
        Receiver receiver = receiverRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + id));
        receiver.setStatus(ReceiverStatus.ACTIVE);
        Receiver updatedReceiver = receiverRepository.save(receiver);
        return mapToResponse(updatedReceiver);
    }

    public void resetPassword(UUID receiverId, String newPassword) {
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + receiverId));

        // Reset password (admin action - no current password verification needed)
        receiver.setPassword(passwordEncoder.encode(newPassword));
        receiverRepository.save(receiver);
    }

    public void deleteReceiver(UUID id) {
        if (!receiverRepository.existsById(id)) {
            throw new RuntimeException("Receiver not found with id: " + id);
        }
        receiverRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public ReceiverLoginResponse login(String username, String password) {
        Receiver receiver = receiverRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Invalid username or password"));

        // Verify password
        if (!passwordEncoder.matches(password, receiver.getPassword())) {
            throw new RuntimeException("Invalid username or password");
        }

        // Check if receiver is active
        if (receiver.getStatus() != ReceiverStatus.ACTIVE) {
            throw new RuntimeException("Receiver account is not active. Status: " + receiver.getStatus());
        }

        // Generate JWT token
        String token = jwtUtil.generateReceiverToken(receiver.getUsername());

        // Map to login response
        ReceiverLoginResponse response = new ReceiverLoginResponse();
        response.setToken(token);
        response.setTokenType("Bearer");
        response.setUserType("MERCHANT");
        response.setId(receiver.getId());
        response.setCompanyName(receiver.getCompanyName());
        response.setManagerName(receiver.getManagerName());
        response.setUsername(receiver.getUsername());
        response.setReceiverPhone(receiver.getReceiverPhone());
        response.setAccountNumber(receiver.getAccountNumber());
        response.setStatus(receiver.getStatus());
        response.setEmail(receiver.getEmail());
        response.setAddress(receiver.getAddress());
        response.setDescription(receiver.getDescription());
        response.setWalletBalance(receiver.getWalletBalance());
        response.setTotalReceived(receiver.getTotalReceived());
        response.setLastTransactionDate(receiver.getLastTransactionDate());
        response.setCreatedAt(receiver.getCreatedAt());
        response.setUpdatedAt(receiver.getUpdatedAt());

        return response;
    }

    @Transactional(readOnly = true)
    public ReceiverWalletResponse getReceiverWallet(UUID id) {
        Receiver receiver = receiverRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + id));

        // Use shared balance if submerchant (parent's balance)
        Receiver balanceOwner = receiver.getParentReceiver() != null ? receiver.getParentReceiver() : receiver;

        ReceiverWalletResponse response = new ReceiverWalletResponse();
        response.setReceiverId(receiver.getId());
        response.setCompanyName(receiver.getCompanyName());
        response.setReceiverPhone(receiver.getReceiverPhone());
        response.setWalletBalance(balanceOwner.getWalletBalance()); // Shared wallet balance
        response.setTotalReceived(balanceOwner.getTotalReceived()); // Shared total received
        response.setAssignedBalance(balanceOwner.getAssignedBalance()); // Shared assigned balance
        response.setRemainingBalance(balanceOwner.getRemainingBalance()); // Shared remaining balance
        response.setDiscountPercentage(balanceOwner.getDiscountPercentage()); // Use balance owner's percentages
        response.setUserBonusPercentage(balanceOwner.getUserBonusPercentage()); // Use balance owner's percentages
        response.setLastTransactionDate(receiver.getLastTransactionDate());
        return response;
    }

    @Transactional(readOnly = true)
    public ReceiverAnalyticsResponse getReceiverAnalytics(UUID receiverId, LocalDateTime fromDate, LocalDateTime toDate, 
                                                          Integer year, UUID categoryId) {
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + receiverId));

        // Handle year filter - if year is provided, set fromDate and toDate for that year
        if (year != null) {
            fromDate = LocalDateTime.of(year, 1, 1, 0, 0);
            toDate = LocalDateTime.of(year, 12, 31, 23, 59, 59);
        }

        // If no date range is provided, use all time
        if (fromDate == null && toDate == null) {
            fromDate = null; // Will be handled as IS NULL in queries
            toDate = null;
        } else {
            // Set time to start/end of day if only date is provided
            if (fromDate != null && fromDate.getHour() == 0 && fromDate.getMinute() == 0 && fromDate.getSecond() == 0) {
                fromDate = fromDate.withHour(0).withMinute(0).withSecond(0);
            }
            if (toDate != null) {
                toDate = toDate.withHour(23).withMinute(59).withSecond(59);
            }
        }

        ReceiverAnalyticsResponse response = new ReceiverAnalyticsResponse();
        response.setReceiverId(receiver.getId());
        response.setCompanyName(receiver.getCompanyName());
        response.setFromDate(fromDate);
        response.setToDate(toDate);

        // Calculate metrics
        BigDecimal totalPaid = transactionRepository.sumSuccessfulAmountByReceiverAndFilters(receiverId, fromDate, toDate, categoryId);
        response.setTotalPaid(totalPaid != null ? totalPaid : BigDecimal.ZERO);

        Long totalTransactions = transactionRepository.countAllTransactionsByReceiverAndFilters(receiverId, fromDate, toDate, categoryId);
        response.setTotalTransactions(totalTransactions != null ? totalTransactions : 0L);

        Long approvedTransactions = transactionRepository.countSuccessfulTransactionsByReceiverAndFilters(receiverId, fromDate, toDate, categoryId);
        response.setApprovedTransactions(approvedTransactions != null ? approvedTransactions : 0L);

        Long totalUsers = transactionRepository.countDistinctUsersByReceiverAndDateRange(receiverId, fromDate, toDate);
        response.setTotalUsers(totalUsers != null ? totalUsers : 0L);

        // Calculate average transaction amount
        if (approvedTransactions != null && approvedTransactions > 0 && totalPaid != null) {
            BigDecimal average = totalPaid.divide(BigDecimal.valueOf(approvedTransactions), 2, RoundingMode.HALF_UP);
            response.setAverageTransactionAmount(average);
        } else {
            response.setAverageTransactionAmount(BigDecimal.ZERO);
        }

        // Get category breakdown (only if categoryId is not specified)
        if (categoryId == null) {
            List<Object[]> categoryBreakdown = transactionRepository.getCategoryBreakdownByReceiver(receiverId, fromDate, toDate);
            Map<UUID, ReceiverAnalyticsResponse.CategoryAnalytics> categoryMap = new HashMap<>();
            
            for (Object[] row : categoryBreakdown) {
                UUID categoryIdValue = (UUID) row[0];
                String categoryName = (String) row[1];
                Long count = ((Number) row[2]).longValue();
                BigDecimal amount = (BigDecimal) row[3];
                
                ReceiverAnalyticsResponse.CategoryAnalytics categoryAnalytics = new ReceiverAnalyticsResponse.CategoryAnalytics();
                categoryAnalytics.setCategoryId(categoryIdValue);
                categoryAnalytics.setCategoryName(categoryName);
                categoryAnalytics.setTransactionCount(count);
                categoryAnalytics.setTotalAmount(amount);
                
                categoryMap.put(categoryIdValue, categoryAnalytics);
            }
            
            response.setCategoryBreakdown(categoryMap);
        }

        return response;
    }

    private ReceiverResponse mapToResponse(Receiver receiver) {
        ReceiverResponse response = new ReceiverResponse();
        response.setId(receiver.getId());
        response.setCompanyName(receiver.getCompanyName());
        response.setManagerName(receiver.getManagerName());
        response.setUsername(receiver.getUsername());
        response.setReceiverPhone(receiver.getReceiverPhone());
        response.setAccountNumber(receiver.getAccountNumber());
        response.setStatus(receiver.getStatus());
        response.setEmail(receiver.getEmail());
        response.setAddress(receiver.getAddress());
        response.setDescription(receiver.getDescription());
        
        // Use shared balance if submerchant (parent's balance), otherwise use own balance
        Receiver balanceOwner = receiver.getParentReceiver() != null ? receiver.getParentReceiver() : receiver;
        response.setWalletBalance(balanceOwner.getWalletBalance()); // Shared wallet balance
        response.setTotalReceived(balanceOwner.getTotalReceived()); // Shared total received
        response.setAssignedBalance(balanceOwner.getAssignedBalance()); // Shared assigned balance
        response.setRemainingBalance(balanceOwner.getRemainingBalance()); // Shared remaining balance
        response.setDiscountPercentage(balanceOwner.getDiscountPercentage()); // Use balance owner's percentages
        response.setUserBonusPercentage(balanceOwner.getUserBonusPercentage()); // Use balance owner's percentages
        
        response.setPendingBalanceAssignments(0); // Default, will be set in getReceiverById if needed
        
        // Submerchant relationship info
        if (receiver.getParentReceiver() != null) {
            response.setParentReceiverId(receiver.getParentReceiver().getId());
            response.setParentReceiverCompanyName(receiver.getParentReceiver().getCompanyName());
            response.setIsMainMerchant(false);
            response.setSubmerchantCount(0);
        } else {
            response.setParentReceiverId(null);
            response.setParentReceiverCompanyName(null);
            response.setIsMainMerchant(true);
            // Count submerchants
            List<Receiver> submerchants = receiverRepository.findByParentReceiverId(receiver.getId());
            response.setSubmerchantCount(submerchants.size());
        }
        
        response.setLastTransactionDate(receiver.getLastTransactionDate());
        response.setCreatedAt(receiver.getCreatedAt());
        response.setUpdatedAt(receiver.getUpdatedAt());
        return response;
    }

    @Transactional(readOnly = true)
    public List<BalanceAssignmentHistoryResponse> getBalanceAssignmentHistory(UUID receiverId) {
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + receiverId));
        
        return balanceAssignmentHistoryRepository.findByReceiverOrderByCreatedAtDesc(receiver)
                .stream()
                .map(this::mapToBalanceAssignmentHistoryResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PaginatedResponse<BalanceAssignmentHistoryResponse> getBalanceAssignmentHistoryPaginated(
            UUID receiverId,
            int page,
            int size,
            String search,
            LocalDateTime fromDate,
            LocalDateTime toDate) {
        // Verify receiver exists
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + receiverId));
        
        // Determine which receivers' history to show
        // If submerchant, show main merchant's history (shared balance)
        // If main merchant, show only their own history
        Receiver balanceOwner = receiver.getParentReceiver() != null ? receiver.getParentReceiver() : receiver;
        UUID balanceOwnerId = balanceOwner.getId();
        
        // Build list of receiver IDs to query (balance owner for shared balance)
        java.util.List<UUID> receiverIds = new java.util.ArrayList<>();
        receiverIds.add(balanceOwnerId);
        
        // Build dynamic query using EntityManager
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT b FROM BalanceAssignmentHistory b ");
        queryBuilder.append("LEFT JOIN FETCH b.receiver r ");
        queryBuilder.append("WHERE b.receiver.id IN :receiverIds ");
        
        // Add search filter
        if (search != null && !search.trim().isEmpty()) {
            queryBuilder.append("AND (LOWER(r.companyName) LIKE LOWER(:search) ");
            queryBuilder.append("OR LOWER(b.assignedBy) LIKE LOWER(:search) ");
            queryBuilder.append("OR LOWER(b.approvedBy) LIKE LOWER(:search) ");
            queryBuilder.append("OR LOWER(b.notes) LIKE LOWER(:search) ");
            queryBuilder.append("OR LOWER(CAST(b.status AS string)) LIKE LOWER(:search)) ");
        }
        
        // Add date range filters
        if (fromDate != null) {
            queryBuilder.append("AND b.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            queryBuilder.append("AND b.createdAt <= :toDate ");
        }
        
        queryBuilder.append("ORDER BY b.createdAt DESC");
        
        // Create query
        Query query = entityManager.createQuery(queryBuilder.toString(), BalanceAssignmentHistory.class);
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
        countQueryBuilder.append("SELECT COUNT(b) FROM BalanceAssignmentHistory b ");
        countQueryBuilder.append("LEFT JOIN b.receiver r ");
        countQueryBuilder.append("WHERE b.receiver.id IN :receiverIds ");
        
        if (search != null && !search.trim().isEmpty()) {
            countQueryBuilder.append("AND (LOWER(r.companyName) LIKE LOWER(:search) ");
            countQueryBuilder.append("OR LOWER(b.assignedBy) LIKE LOWER(:search) ");
            countQueryBuilder.append("OR LOWER(b.approvedBy) LIKE LOWER(:search) ");
            countQueryBuilder.append("OR LOWER(b.notes) LIKE LOWER(:search) ");
            countQueryBuilder.append("OR LOWER(CAST(b.status AS string)) LIKE LOWER(:search)) ");
        }
        
        if (fromDate != null) {
            countQueryBuilder.append("AND b.createdAt >= :fromDate ");
        }
        if (toDate != null) {
            countQueryBuilder.append("AND b.createdAt <= :toDate ");
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
        List<BalanceAssignmentHistory> histories = (List<BalanceAssignmentHistory>) query.getResultList();
        
        // Convert to BalanceAssignmentHistoryResponse
        List<BalanceAssignmentHistoryResponse> content = histories.stream()
                .map(this::mapToBalanceAssignmentHistoryResponse)
                .collect(Collectors.toList());
        
        // Calculate pagination metadata
        int totalPages = (int) Math.ceil((double) totalElements / size);
        
        PaginatedResponse<BalanceAssignmentHistoryResponse> response = new PaginatedResponse<>();
        response.setContent(content);
        response.setTotalElements(totalElements);
        response.setTotalPages(totalPages);
        response.setCurrentPage(page);
        response.setPageSize(size);
        response.setFirst(page == 0);
        response.setLast(page >= totalPages - 1);
        
        return response;
    }

    private BalanceAssignmentHistoryResponse mapToBalanceAssignmentHistoryResponse(BalanceAssignmentHistory history) {
        BalanceAssignmentHistoryResponse response = new BalanceAssignmentHistoryResponse();
        response.setId(history.getId());
        response.setReceiverId(history.getReceiver().getId());
        response.setReceiverCompanyName(history.getReceiver().getCompanyName());
        response.setAssignedBalance(history.getAssignedBalance());
        response.setPreviousAssignedBalance(history.getPreviousAssignedBalance());
        response.setBalanceDifference(history.getBalanceDifference());
        response.setAssignedBy(history.getAssignedBy());
        response.setNotes(history.getNotes());
        response.setStatus(history.getStatus());
        response.setApprovedBy(history.getApprovedBy());
        response.setApprovedAt(history.getApprovedAt());
        response.setMopayTransactionId(history.getMopayTransactionId());
        response.setCreatedAt(history.getCreatedAt());
        return response;
    }

    public BalanceAssignmentHistoryResponse approveBalanceAssignment(UUID receiverId, UUID historyId, boolean approve) {
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + receiverId));
        
        BalanceAssignmentHistory history = balanceAssignmentHistoryRepository.findById(historyId)
                .orElseThrow(() -> new RuntimeException("Balance assignment history not found with id: " + historyId));
        
        // Verify this history belongs to this receiver
        if (!history.getReceiver().getId().equals(receiverId)) {
            throw new RuntimeException("Balance assignment history does not belong to this receiver");
        }
        
        // Only allow approval/rejection if status is PENDING
        if (history.getStatus() != BalanceAssignmentStatus.PENDING) {
            throw new RuntimeException("Balance assignment has already been " + history.getStatus().name().toLowerCase());
        }
        
        if (approve) {
            history.setStatus(BalanceAssignmentStatus.APPROVED);
            history.setApprovedBy(receiver.getUsername());
            history.setApprovedAt(LocalDateTime.now());
            
            // Determine balance owner: if receiver is a submerchant, update parent's balance
            Receiver balanceOwner = receiver.getParentReceiver() != null ? receiver.getParentReceiver() : receiver;
            
            // Apply the balance change to balance owner (main merchant if submerchant, otherwise receiver itself)
            BigDecimal currentAssigned = balanceOwner.getAssignedBalance();
            BigDecimal newAssignedBalance = history.getAssignedBalance();
            BigDecimal currentRemaining = balanceOwner.getRemainingBalance();
            
            // Calculate the base difference (new assigned - current assigned)
            BigDecimal baseDifference = newAssignedBalance.subtract(currentAssigned);
            
            // Add discount percentage as bonus to remaining balance (use balance owner's discount percentage)
            // Example: If assigning 10,000 with 10% discount, remainingBalance gets 10,000 + 1,000 = 11,000
            BigDecimal discountPercentage = balanceOwner.getDiscountPercentage() != null ? balanceOwner.getDiscountPercentage() : BigDecimal.ZERO;
            BigDecimal discountBonus = baseDifference.multiply(discountPercentage).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            BigDecimal totalBonus = baseDifference.add(discountBonus);
            
            // Update remaining balance: current + base difference + discount bonus
            BigDecimal newRemainingBalance = currentRemaining.add(totalBonus);
            
            // Ensure remaining balance doesn't go below zero
            if (newRemainingBalance.compareTo(BigDecimal.ZERO) < 0) {
                newRemainingBalance = BigDecimal.ZERO;
            }
            
            // Update balance owner (shared balance)
            balanceOwner.setAssignedBalance(newAssignedBalance);
            balanceOwner.setRemainingBalance(newRemainingBalance);
            receiverRepository.save(balanceOwner);
            
            // Also update receiver's assigned balance for tracking (but not remaining balance - that's shared)
            receiver.setAssignedBalance(newAssignedBalance);
            receiverRepository.save(receiver);
        } else {
            history.setStatus(BalanceAssignmentStatus.REJECTED);
            history.setApprovedBy(receiver.getUsername());
            history.setApprovedAt(LocalDateTime.now());
        }
        
        BalanceAssignmentHistory updatedHistory = balanceAssignmentHistoryRepository.save(history);
        return mapToBalanceAssignmentHistoryResponse(updatedHistory);
    }

    /**
     * Normalize phone number to 12 digits format (remove + and other non-digit characters)
     * MoPay API requires 12-digit phone numbers
     */
    private String normalizePhoneTo12Digits(String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            throw new RuntimeException("Phone number cannot be empty");
        }
        // Remove all non-digit characters
        String digitsOnly = phone.replaceAll("[^0-9]", "");
        
        // If phone starts with country code 250, ensure it's 12 digits
        if (digitsOnly.startsWith("250") && digitsOnly.length() == 12) {
            return digitsOnly;
        } else if (digitsOnly.startsWith("250") && digitsOnly.length() == 9) {
            // Already has country code but missing leading 0, add it
            return "250" + digitsOnly.substring(3);
        } else if (digitsOnly.length() == 9) {
            // 9 digits without country code, add 250
            return "250" + digitsOnly;
        } else if (digitsOnly.length() == 10 && digitsOnly.startsWith("0")) {
            // 10 digits starting with 0, replace 0 with 250
            return "250" + digitsOnly.substring(1);
        } else if (digitsOnly.length() == 12) {
            // Already 12 digits
            return digitsOnly;
        } else {
            throw new RuntimeException("Invalid phone number format. Expected 9-12 digits, got: " + digitsOnly.length());
        }
    }

    @Transactional(readOnly = true)
    public ReceiverDashboardResponse getReceiverDashboard(UUID receiverId) {
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + receiverId));

        ReceiverDashboardResponse response = new ReceiverDashboardResponse();
        
        // Basic receiver info
        response.setReceiverId(receiver.getId());
        response.setCompanyName(receiver.getCompanyName());
        response.setManagerName(receiver.getManagerName());
        response.setReceiverPhone(receiver.getReceiverPhone());
        response.setEmail(receiver.getEmail());
        response.setAddress(receiver.getAddress());
        
        // Wallet information - use shared balance if submerchant (parent's balance)
        Receiver balanceOwner = receiver.getParentReceiver() != null ? receiver.getParentReceiver() : receiver;
        response.setWalletBalance(balanceOwner.getWalletBalance()); // Shared wallet balance
        response.setTotalReceived(balanceOwner.getTotalReceived()); // Shared total received
        response.setAssignedBalance(balanceOwner.getAssignedBalance()); // Shared assigned balance
        response.setRemainingBalance(balanceOwner.getRemainingBalance()); // Shared remaining balance
        response.setDiscountPercentage(balanceOwner.getDiscountPercentage()); // Use balance owner's percentages
        response.setUserBonusPercentage(balanceOwner.getUserBonusPercentage()); // Use balance owner's percentages
        
        // Count pending balance assignments - use balance owner's pending assignments (shared)
        List<BalanceAssignmentHistory> pendingAssignments = balanceAssignmentHistoryRepository.findPendingByReceiverId(balanceOwner.getId());
        response.setPendingBalanceAssignments(pendingAssignments.size());
        
        // Check if main merchant or submerchant
        boolean isMainMerchant = receiver.getParentReceiver() == null;
        response.setIsMainMerchant(isMainMerchant);
        
        if (isMainMerchant) {
            response.setParentReceiverId(null);
            response.setParentReceiverCompanyName(null);
            
            // Get submerchants
            List<Receiver> submerchants = receiverRepository.findByParentReceiverId(receiverId);
            List<ReceiverDashboardResponse.SubmerchantInfo> submerchantInfos = submerchants.stream()
                    .map(this::mapToSubmerchantInfo)
                    .collect(Collectors.toList());
            response.setSubmerchants(submerchantInfos);
            
            // Calculate full statistics (including all submerchants)
            ReceiverDashboardResponse.FullStatistics fullStats = calculateFullStatistics(receiverId, submerchants);
            response.setFullStatistics(fullStats);
        } else {
            response.setParentReceiverId(receiver.getParentReceiver().getId());
            response.setParentReceiverCompanyName(receiver.getParentReceiver().getCompanyName());
            response.setSubmerchants(null);
            response.setFullStatistics(null);
        }
        
        // Transaction statistics and recent transactions
        // For main merchant: show all transactions (main + all submerchants)
        // For submerchant: show only their own transactions
        if (isMainMerchant) {
            // Main merchant: Calculate stats and get transactions for all (main + submerchants)
            // Get all submerchants for stats calculation
            List<Receiver> submerchants = receiverRepository.findByParentReceiverId(receiverId);
            
            // Calculate aggregated statistics
            Long totalTransactions = transactionRepository.countAllTransactionsByReceiver(receiverId);
            BigDecimal totalRevenue = transactionRepository.sumSuccessfulAmountByReceiver(receiverId);
            Long totalCustomers = transactionRepository.countDistinctUsersByReceiver(receiverId);
            
            // Aggregate submerchant stats
            for (Receiver submerchant : submerchants) {
                Long subTransactions = transactionRepository.countAllTransactionsByReceiver(submerchant.getId());
                BigDecimal subRevenue = transactionRepository.sumSuccessfulAmountByReceiver(submerchant.getId());
                Long subCustomers = transactionRepository.countDistinctUsersByReceiver(submerchant.getId());
                
                totalTransactions = (totalTransactions != null ? totalTransactions : 0L) + (subTransactions != null ? subTransactions : 0L);
                totalRevenue = (totalRevenue != null ? totalRevenue : BigDecimal.ZERO).add(subRevenue != null ? subRevenue : BigDecimal.ZERO);
                totalCustomers = (totalCustomers != null ? totalCustomers : 0L) + (subCustomers != null ? subCustomers : 0L);
            }
            
            response.setTotalTransactions(totalTransactions != null ? totalTransactions : 0L);
            response.setTotalRevenue(totalRevenue != null ? totalRevenue : BigDecimal.ZERO);
            response.setTotalCustomers(totalCustomers != null ? totalCustomers : 0L);
            
            // Get all transactions (main + all submerchants) for recent transactions
            List<Transaction> allTransactions = new java.util.ArrayList<>();
            allTransactions.addAll(transactionRepository.findByReceiverOrderByCreatedAtDescWithUser(receiver));
            for (Receiver submerchant : submerchants) {
                allTransactions.addAll(transactionRepository.findByReceiverOrderByCreatedAtDescWithUser(submerchant));
            }
            
            // Sort by date descending and limit to 5
            allTransactions.sort((t1, t2) -> {
                if (t1.getCreatedAt() == null && t2.getCreatedAt() == null) return 0;
                if (t1.getCreatedAt() == null) return 1;
                if (t2.getCreatedAt() == null) return -1;
                return t2.getCreatedAt().compareTo(t1.getCreatedAt());
            });
            
            response.setRecentTransactions(allTransactions.stream()
                    .limit(5)
                    .map(t -> mapToRecentTransaction(t, receiverId))
                    .collect(Collectors.toList()));
        } else {
            // Submerchant: Show only their own transactions
            // Calculate stats for this submerchant only
            Long totalTransactions = transactionRepository.countAllTransactionsByReceiver(receiverId);
            BigDecimal totalRevenue = transactionRepository.sumSuccessfulAmountByReceiver(receiverId);
            Long totalCustomers = transactionRepository.countDistinctUsersByReceiver(receiverId);
            
            response.setTotalTransactions(totalTransactions != null ? totalTransactions : 0L);
            response.setTotalRevenue(totalRevenue != null ? totalRevenue : BigDecimal.ZERO);
            response.setTotalCustomers(totalCustomers != null ? totalCustomers : 0L);
            
            // Get only this submerchant's recent transactions
            List<Transaction> recentTransactions = transactionRepository.findByReceiverOrderByCreatedAtDescWithUser(receiver)
                    .stream()
                    .limit(5)
                    .collect(Collectors.toList());
            response.setRecentTransactions(recentTransactions.stream()
                    .map(t -> mapToRecentTransaction(t, receiverId))
                    .collect(Collectors.toList()));
        }
        
        // Recent balance assignments (5 most recent) - use balance owner's history (already shared)
        List<BalanceAssignmentHistory> balanceHistories = balanceAssignmentHistoryRepository
                .findByReceiverOrderByCreatedAtDesc(balanceOwner).stream()
                .limit(5)
                .collect(Collectors.toList());
        response.setRecentBalanceAssignments(balanceHistories.stream()
                .map(this::mapToBalanceAssignmentSummary)
                .collect(Collectors.toList()));
        
        return response;
    }

    private ReceiverDashboardResponse.SubmerchantInfo mapToSubmerchantInfo(Receiver submerchant) {
        ReceiverDashboardResponse.SubmerchantInfo info = new ReceiverDashboardResponse.SubmerchantInfo();
        info.setSubmerchantId(submerchant.getId());
        info.setCompanyName(submerchant.getCompanyName());
        info.setManagerName(submerchant.getManagerName());
        info.setReceiverPhone(submerchant.getReceiverPhone());
        info.setWalletBalance(submerchant.getWalletBalance());
        info.setTotalReceived(submerchant.getTotalReceived());
        info.setRemainingBalance(submerchant.getRemainingBalance());
        info.setStatus(submerchant.getStatus());
        info.setCreatedAt(submerchant.getCreatedAt());
        
        // Get transaction stats for submerchant
        Long subTransactions = transactionRepository.countAllTransactionsByReceiver(submerchant.getId());
        info.setTotalTransactions(subTransactions != null ? subTransactions : 0L);
        
        BigDecimal subRevenue = transactionRepository.sumSuccessfulAmountByReceiver(submerchant.getId());
        info.setTotalRevenue(subRevenue != null ? subRevenue : BigDecimal.ZERO);
        
        return info;
    }

    private ReceiverDashboardResponse.FullStatistics calculateFullStatistics(UUID mainReceiverId, List<Receiver> submerchants) {
        ReceiverDashboardResponse.FullStatistics stats = new ReceiverDashboardResponse.FullStatistics();
        
        // Get main receiver stats
        Long mainTransactions = transactionRepository.countAllTransactionsByReceiver(mainReceiverId);
        BigDecimal mainRevenue = transactionRepository.sumSuccessfulAmountByReceiver(mainReceiverId);
        Long mainCustomers = transactionRepository.countDistinctUsersByReceiver(mainReceiverId);
        
        Receiver mainReceiver = receiverRepository.findById(mainReceiverId).orElseThrow();
        BigDecimal mainWalletBalance = mainReceiver.getWalletBalance();
        BigDecimal mainRemainingBalance = mainReceiver.getRemainingBalance();
        
        // Aggregate submerchant stats
        long totalTransactions = mainTransactions != null ? mainTransactions : 0L;
        BigDecimal totalRevenue = mainRevenue != null ? mainRevenue : BigDecimal.ZERO;
        long totalCustomers = mainCustomers != null ? mainCustomers : 0L;
        BigDecimal combinedWalletBalance = mainWalletBalance;
        BigDecimal combinedRemainingBalance = mainRemainingBalance;
        
        for (Receiver submerchant : submerchants) {
            Long subTransactions = transactionRepository.countAllTransactionsByReceiver(submerchant.getId());
            BigDecimal subRevenue = transactionRepository.sumSuccessfulAmountByReceiver(submerchant.getId());
            Long subCustomers = transactionRepository.countDistinctUsersByReceiver(submerchant.getId());
            
            totalTransactions += (subTransactions != null ? subTransactions : 0L);
            totalRevenue = totalRevenue.add(subRevenue != null ? subRevenue : BigDecimal.ZERO);
            combinedWalletBalance = combinedWalletBalance.add(submerchant.getWalletBalance());
            combinedRemainingBalance = combinedRemainingBalance.add(submerchant.getRemainingBalance());
            
            // For customers, we need distinct count - this is approximate
            totalCustomers += (subCustomers != null ? subCustomers : 0L);
        }
        
        stats.setTotalTransactions(totalTransactions);
        stats.setTotalRevenue(totalRevenue);
        stats.setTotalCustomers(totalCustomers);
        stats.setTotalSubmerchants((long) submerchants.size());
        stats.setCombinedWalletBalance(combinedWalletBalance);
        stats.setCombinedRemainingBalance(combinedRemainingBalance);
        
        return stats;
    }

    private ReceiverDashboardResponse.RecentTransaction mapToRecentTransaction(Transaction transaction, UUID mainReceiverId) {
        ReceiverDashboardResponse.RecentTransaction recent = new ReceiverDashboardResponse.RecentTransaction();
        recent.setTransactionId(transaction.getId());
        recent.setUserId(transaction.getUser().getId());
        recent.setUserName(transaction.getUser().getFullNames());
        recent.setUserPhone(transaction.getUser().getPhoneNumber());
        recent.setAmount(transaction.getAmount());
        recent.setDiscountAmount(transaction.getDiscountAmount());
        recent.setUserBonusAmount(transaction.getUserBonusAmount());
        recent.setStatus(transaction.getStatus());
        if (transaction.getPaymentCategory() != null) {
            recent.setPaymentCategoryName(transaction.getPaymentCategory().getName());
        }
        recent.setCreatedAt(transaction.getCreatedAt());
        
        // Add receiver information
        if (transaction.getReceiver() != null) {
            recent.setReceiverId(transaction.getReceiver().getId());
            recent.setReceiverCompanyName(transaction.getReceiver().getCompanyName());
            // Check if this transaction was made by a submerchant (not the main receiver)
            recent.setIsSubmerchant(!transaction.getReceiver().getId().equals(mainReceiverId));
        }
        
        return recent;
    }

    private ReceiverDashboardResponse.BalanceAssignmentSummary mapToBalanceAssignmentSummary(BalanceAssignmentHistory history) {
        ReceiverDashboardResponse.BalanceAssignmentSummary summary = new ReceiverDashboardResponse.BalanceAssignmentSummary();
        summary.setHistoryId(history.getId());
        summary.setAssignedBalance(history.getAssignedBalance());
        summary.setBalanceDifference(history.getBalanceDifference());
        summary.setStatus(history.getStatus());
        summary.setNotes(history.getNotes());
        summary.setCreatedAt(history.getCreatedAt());
        summary.setApprovedAt(history.getApprovedAt());
        summary.setApprovedBy(history.getApprovedBy());
        return summary;
    }

    @Transactional
    public ReceiverResponse createSubmerchant(UUID mainReceiverId, CreateReceiverRequest request) {
        // Verify main receiver exists and is a main merchant (has no parent)
        Receiver mainReceiver = receiverRepository.findById(mainReceiverId)
                .orElseThrow(() -> new RuntimeException("Main receiver not found with id: " + mainReceiverId));
        
        if (mainReceiver.getParentReceiver() != null) {
            throw new RuntimeException("Cannot create submerchant. The specified receiver is already a submerchant.");
        }
        
        // Check if username already exists
        if (receiverRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already exists: " + request.getUsername());
        }

        // Check if phone number already exists
        if (receiverRepository.existsByReceiverPhone(request.getReceiverPhone())) {
            throw new RuntimeException("Receiver phone number already exists: " + request.getReceiverPhone());
        }

        // Normalize email: convert empty/blank strings to null
        String email = (request.getEmail() != null && !request.getEmail().trim().isEmpty()) 
                ? request.getEmail().trim() 
                : null;

        // Validate email format if provided
        if (email != null) {
            String emailRegex = "^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$";
            if (!email.matches(emailRegex)) {
                throw new RuntimeException("Invalid email format");
            }
            // Check if email already exists
            if (receiverRepository.existsByEmail(email)) {
                throw new RuntimeException("Email already exists: " + email);
            }
        }

        // Create new receiver
        Receiver submerchant = new Receiver();
        submerchant.setCompanyName(request.getCompanyName());
        submerchant.setManagerName(request.getManagerName());
        submerchant.setUsername(request.getUsername());
        submerchant.setPassword(passwordEncoder.encode(request.getPassword())); // Hash the password
        submerchant.setReceiverPhone(request.getReceiverPhone());
        submerchant.setAccountNumber(request.getAccountNumber());
        submerchant.setStatus(request.getStatus() != null ? request.getStatus() : ReceiverStatus.NOT_ACTIVE);
        submerchant.setEmail(email);
        submerchant.setAddress(request.getAddress());
        submerchant.setDescription(request.getDescription());
        
        // Set parent receiver (link as submerchant)
        submerchant.setParentReceiver(mainReceiver);

        Receiver savedSubmerchant = receiverRepository.save(submerchant);
        return mapToResponse(savedSubmerchant);
    }

    @Transactional
    public ReceiverResponse linkExistingReceiverAsSubmerchant(UUID mainReceiverId, CreateSubmerchantRequest request) {
        Receiver mainReceiver = receiverRepository.findById(mainReceiverId)
                .orElseThrow(() -> new RuntimeException("Main receiver not found with id: " + mainReceiverId));
        
        // Verify main receiver has no parent (is a main merchant)
        if (mainReceiver.getParentReceiver() != null) {
            throw new RuntimeException("Cannot link submerchant. The specified receiver is already a submerchant.");
        }
        
        Receiver submerchant = receiverRepository.findById(request.getSubmerchantReceiverId())
                .orElseThrow(() -> new RuntimeException("Submerchant receiver not found with id: " + request.getSubmerchantReceiverId()));
        
        // Verify submerchant is not already linked
        if (submerchant.getParentReceiver() != null) {
            throw new RuntimeException("Receiver is already linked as a submerchant to another merchant.");
        }
        
        // Verify not linking to itself
        if (mainReceiverId.equals(request.getSubmerchantReceiverId())) {
            throw new RuntimeException("Cannot link a receiver to itself as a submerchant.");
        }
        
        // Link submerchant to main receiver
        submerchant.setParentReceiver(mainReceiver);
        receiverRepository.save(submerchant);
        
        return mapToResponse(submerchant);
    }

    @Transactional(readOnly = true)
    public List<ReceiverResponse> getSubmerchants(UUID mainReceiverId) {
        // Verify main receiver exists
        Receiver mainReceiver = receiverRepository.findById(mainReceiverId)
                .orElseThrow(() -> new RuntimeException("Main receiver not found with id: " + mainReceiverId));
        
        List<ReceiverResponse> result = new java.util.ArrayList<>();
        
        // Add main merchant first
        result.add(mapToResponse(mainReceiver));
        
        // Add all submerchants
        List<Receiver> submerchants = receiverRepository.findByParentReceiverId(mainReceiverId);
        List<ReceiverResponse> submerchantResponses = submerchants.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        result.addAll(submerchantResponses);
        
        return result;
    }

    @Transactional(readOnly = true)
    public List<ReceiverResponse> getAllMainMerchants() {
        // Get all receivers without a parent (main merchants)
        List<Receiver> mainMerchants = receiverRepository.findByParentReceiverIsNull();
        return mainMerchants.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ReceiverResponse unlinkSubmerchant(UUID submerchantId) {
        Receiver submerchant = receiverRepository.findById(submerchantId)
                .orElseThrow(() -> new RuntimeException("Submerchant receiver not found with id: " + submerchantId));
        
        if (submerchant.getParentReceiver() == null) {
            throw new RuntimeException("Receiver is not a submerchant. Cannot unlink.");
        }
        
        submerchant.setParentReceiver(null);
        receiverRepository.save(submerchant);
        
        return mapToResponse(submerchant);
    }

    @Transactional
    public ReceiverResponse updateSubmerchantParent(UUID submerchantId, CreateSubmerchantRequest request) {
        Receiver submerchant = receiverRepository.findById(submerchantId)
                .orElseThrow(() -> new RuntimeException("Submerchant receiver not found with id: " + submerchantId));
        
        Receiver newParentReceiver = receiverRepository.findById(request.getSubmerchantReceiverId())
                .orElseThrow(() -> new RuntimeException("New parent receiver not found with id: " + request.getSubmerchantReceiverId()));
        
        // Verify new parent is not already a submerchant
        if (newParentReceiver.getParentReceiver() != null) {
            throw new RuntimeException("Cannot set parent. The specified receiver is already a submerchant.");
        }
        
        // Verify not linking to itself
        if (submerchantId.equals(request.getSubmerchantReceiverId())) {
            throw new RuntimeException("Cannot link a receiver to itself as a parent.");
        }
        
        // Update parent
        submerchant.setParentReceiver(newParentReceiver);
        receiverRepository.save(submerchant);
        
        return mapToResponse(submerchant);
    }

    @Transactional
    public ReceiverResponse updateSubmerchant(UUID mainReceiverId, UUID submerchantId, UpdateReceiverRequest request) {
        // Verify main receiver exists
        if (!receiverRepository.existsById(mainReceiverId)) {
            throw new RuntimeException("Main receiver not found with id: " + mainReceiverId);
        }
        
        // Verify submerchant exists and belongs to the main receiver
        Receiver submerchant = receiverRepository.findById(submerchantId)
                .orElseThrow(() -> new RuntimeException("Submerchant receiver not found with id: " + submerchantId));
        
        if (submerchant.getParentReceiver() == null || !submerchant.getParentReceiver().getId().equals(mainReceiverId)) {
            throw new RuntimeException("Receiver is not a submerchant of the specified main merchant.");
        }
        
        // Check if username is being changed and if it conflicts
        if (request.getUsername() != null && !submerchant.getUsername().equals(request.getUsername())) {
            if (receiverRepository.existsByUsername(request.getUsername())) {
                throw new RuntimeException("Username already exists: " + request.getUsername());
            }
            submerchant.setUsername(request.getUsername());
        }

        // Check if phone is being changed and if it conflicts
        if (request.getReceiverPhone() != null && !submerchant.getReceiverPhone().equals(request.getReceiverPhone())) {
            if (receiverRepository.existsByReceiverPhone(request.getReceiverPhone())) {
                throw new RuntimeException("Receiver phone number already exists: " + request.getReceiverPhone());
            }
            submerchant.setReceiverPhone(request.getReceiverPhone());
        }

        // Update password if provided
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            submerchant.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        // Update other fields
        if (request.getCompanyName() != null) {
            submerchant.setCompanyName(request.getCompanyName());
        }
        if (request.getManagerName() != null) {
            submerchant.setManagerName(request.getManagerName());
        }
        if (request.getAccountNumber() != null) {
            submerchant.setAccountNumber(request.getAccountNumber());
        }
        if (request.getStatus() != null) {
            submerchant.setStatus(request.getStatus());
        }
        if (request.getEmail() != null) {
            // Normalize email: convert empty/blank strings to null
            String email = request.getEmail().trim().isEmpty() ? null : request.getEmail().trim();
            
            if (email != null) {
                String emailRegex = "^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$";
                if (!email.matches(emailRegex)) {
                    throw new RuntimeException("Invalid email format");
                }
                // Check if email already exists (for other receivers)
                if (!submerchant.getEmail().equals(email) && receiverRepository.existsByEmail(email)) {
                    throw new RuntimeException("Email already exists: " + email);
                }
            }
            submerchant.setEmail(email);
        }
        if (request.getAddress() != null) {
            submerchant.setAddress(request.getAddress());
        }
        if (request.getDescription() != null) {
            submerchant.setDescription(request.getDescription());
        }
        
        // Note: assignedBalance, discountPercentage, userBonusPercentage are typically managed 
        // through the balance assignment endpoint, but we can update them here if provided
        // For now, we'll leave these as they are managed separately
        
        Receiver updatedSubmerchant = receiverRepository.save(submerchant);
        return mapToResponse(updatedSubmerchant);
    }

    @Transactional
    public void deleteSubmerchant(UUID mainReceiverId, UUID submerchantId) {
        // Verify main receiver exists
        if (!receiverRepository.existsById(mainReceiverId)) {
            throw new RuntimeException("Main receiver not found with id: " + mainReceiverId);
        }
        
        // Verify submerchant exists and belongs to the main receiver
        Receiver submerchant = receiverRepository.findById(submerchantId)
                .orElseThrow(() -> new RuntimeException("Submerchant receiver not found with id: " + submerchantId));
        
        if (submerchant.getParentReceiver() == null || !submerchant.getParentReceiver().getId().equals(mainReceiverId)) {
            throw new RuntimeException("Receiver is not a submerchant of the specified main merchant.");
        }
        
        // Delete the submerchant (this will cascade delete related records if configured)
        receiverRepository.deleteById(submerchantId);
    }
}

