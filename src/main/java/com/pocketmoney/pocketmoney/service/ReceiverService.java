package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.BalanceAssignmentHistoryResponse;
import com.pocketmoney.pocketmoney.dto.CreateReceiverRequest;
import com.pocketmoney.pocketmoney.entity.BalanceAssignmentStatus;
import com.pocketmoney.pocketmoney.dto.ReceiverAnalyticsResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverLoginResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverWalletResponse;
import com.pocketmoney.pocketmoney.dto.UpdateReceiverRequest;
import com.pocketmoney.pocketmoney.entity.BalanceAssignmentHistory;
import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import com.pocketmoney.pocketmoney.repository.BalanceAssignmentHistoryRepository;
import com.pocketmoney.pocketmoney.repository.ReceiverRepository;
import com.pocketmoney.pocketmoney.repository.TransactionRepository;
import com.pocketmoney.pocketmoney.util.JwtUtil;
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

    public ReceiverService(ReceiverRepository receiverRepository, TransactionRepository transactionRepository,
                          BalanceAssignmentHistoryRepository balanceAssignmentHistoryRepository,
                          PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.receiverRepository = receiverRepository;
        this.transactionRepository = transactionRepository;
        this.balanceAssignmentHistoryRepository = balanceAssignmentHistoryRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
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
                BalanceAssignmentHistory history = new BalanceAssignmentHistory();
                history.setReceiver(receiver);
                history.setAssignedBalance(newAssignedBalance);
                history.setPreviousAssignedBalance(currentAssigned);
                history.setBalanceDifference(newAssignedBalance.subtract(currentAssigned));
                history.setAssignedBy("ADMIN"); // TODO: Get from authentication context
                history.setNotes("Balance assignment updated");
                history.setStatus(BalanceAssignmentStatus.PENDING); // Default status is PENDING
                balanceAssignmentHistoryRepository.save(history);
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

        ReceiverWalletResponse response = new ReceiverWalletResponse();
        response.setReceiverId(receiver.getId());
        response.setCompanyName(receiver.getCompanyName());
        response.setReceiverPhone(receiver.getReceiverPhone());
        response.setWalletBalance(receiver.getWalletBalance());
        response.setTotalReceived(receiver.getTotalReceived());
        response.setAssignedBalance(receiver.getAssignedBalance());
        response.setRemainingBalance(receiver.getRemainingBalance());
        response.setDiscountPercentage(receiver.getDiscountPercentage());
        response.setUserBonusPercentage(receiver.getUserBonusPercentage());
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
        response.setWalletBalance(receiver.getWalletBalance());
        response.setTotalReceived(receiver.getTotalReceived());
        response.setAssignedBalance(receiver.getAssignedBalance());
        response.setRemainingBalance(receiver.getRemainingBalance());
        response.setDiscountPercentage(receiver.getDiscountPercentage());
        response.setUserBonusPercentage(receiver.getUserBonusPercentage());
        response.setPendingBalanceAssignments(0); // Default, will be set in getReceiverById if needed
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
            
            // Apply the balance change to receiver
            BigDecimal currentAssigned = receiver.getAssignedBalance();
            BigDecimal newAssignedBalance = history.getAssignedBalance();
            BigDecimal currentRemaining = receiver.getRemainingBalance();
            
            // Calculate the difference and update remaining balance
            BigDecimal difference = newAssignedBalance.subtract(currentAssigned);
            BigDecimal newRemainingBalance = currentRemaining.add(difference);
            
            // Ensure remaining balance doesn't go below zero
            if (newRemainingBalance.compareTo(BigDecimal.ZERO) < 0) {
                newRemainingBalance = BigDecimal.ZERO;
            }
            
            receiver.setAssignedBalance(newAssignedBalance);
            receiver.setRemainingBalance(newRemainingBalance);
            receiverRepository.save(receiver);
        } else {
            history.setStatus(BalanceAssignmentStatus.REJECTED);
            history.setApprovedBy(receiver.getUsername());
            history.setApprovedAt(LocalDateTime.now());
        }
        
        BalanceAssignmentHistory updatedHistory = balanceAssignmentHistoryRepository.save(history);
        return mapToBalanceAssignmentHistoryResponse(updatedHistory);
    }
}

