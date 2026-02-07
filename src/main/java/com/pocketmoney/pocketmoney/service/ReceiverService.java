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
import com.pocketmoney.pocketmoney.repository.PaymentCommissionSettingRepository;
import com.pocketmoney.pocketmoney.repository.MerchantUserBalanceRepository;
import com.pocketmoney.pocketmoney.entity.MerchantUserBalance;
import com.pocketmoney.pocketmoney.entity.PaymentCommissionSetting;
import com.pocketmoney.pocketmoney.util.JwtUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(ReceiverService.class);

    private final ReceiverRepository receiverRepository;
    private final TransactionRepository transactionRepository;
    private final BalanceAssignmentHistoryRepository balanceAssignmentHistoryRepository;
    private final PaymentCommissionSettingRepository paymentCommissionSettingRepository;
    private final MerchantUserBalanceRepository merchantUserBalanceRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final MoPayService moPayService;
    private final EntityManager entityManager;
    private final MessagingService messagingService;
    private final WhatsAppService whatsAppService;

    public ReceiverService(ReceiverRepository receiverRepository, TransactionRepository transactionRepository,
                          BalanceAssignmentHistoryRepository balanceAssignmentHistoryRepository,
                          PaymentCommissionSettingRepository paymentCommissionSettingRepository,
                          MerchantUserBalanceRepository merchantUserBalanceRepository,
                          PasswordEncoder passwordEncoder, JwtUtil jwtUtil, MoPayService moPayService,
                          EntityManager entityManager, MessagingService messagingService, WhatsAppService whatsAppService) {
        this.receiverRepository = receiverRepository;
        this.transactionRepository = transactionRepository;
        this.balanceAssignmentHistoryRepository = balanceAssignmentHistoryRepository;
        this.paymentCommissionSettingRepository = paymentCommissionSettingRepository;
        this.merchantUserBalanceRepository = merchantUserBalanceRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.moPayService = moPayService;
        this.entityManager = entityManager;
        this.messagingService = messagingService;
        this.whatsAppService = whatsAppService;
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
        receiver.setIsFlexible(request.getIsFlexible() != null ? request.getIsFlexible() : false);

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
        // Get all receivers
        List<Receiver> allReceivers = receiverRepository.findAll();
        
        // Group by main merchant: main merchants first, then their submerchants
        return groupReceiversByMainMerchant(allReceivers);
    }

    @Transactional(readOnly = true)
    public List<ReceiverResponse> getActiveReceivers() {
        // Get all active receivers
        List<Receiver> activeReceivers = receiverRepository.findByStatus(ReceiverStatus.ACTIVE);
        
        // Group by main merchant: main merchants first, then their submerchants
        return groupReceiversByMainMerchant(activeReceivers);
    }

    @Transactional(readOnly = true)
    public List<ReceiverResponse> getReceiversByStatus(ReceiverStatus status) {
        // Get all receivers with the specified status
        List<Receiver> receivers = receiverRepository.findByStatus(status);
        
        // Group by main merchant: main merchants first, then their submerchants
        return groupReceiversByMainMerchant(receivers);
    }
    
    /**
     * Groups receivers by main merchant: main merchants first, followed by their submerchants.
     * Receivers without a parent (main merchants) appear first, then their submerchants.
     */
    private List<ReceiverResponse> groupReceiversByMainMerchant(List<Receiver> receivers) {
        List<ReceiverResponse> result = new java.util.ArrayList<>();
        
        // Separate main merchants and submerchants
        List<Receiver> mainMerchants = new java.util.ArrayList<>();
        java.util.Map<UUID, List<Receiver>> submerchantsByParent = new java.util.HashMap<>();
        
        for (Receiver receiver : receivers) {
            if (receiver.getParentReceiver() == null) {
                // Main merchant (no parent)
                mainMerchants.add(receiver);
            } else {
                // Submerchant - group by parent ID
                UUID parentId = receiver.getParentReceiver().getId();
                submerchantsByParent.computeIfAbsent(parentId, k -> new java.util.ArrayList<>()).add(receiver);
            }
        }
        
        // Sort main merchants by company name
        mainMerchants.sort((a, b) -> {
            String nameA = a.getCompanyName() != null ? a.getCompanyName() : "";
            String nameB = b.getCompanyName() != null ? b.getCompanyName() : "";
            return nameA.compareToIgnoreCase(nameB);
        });
        
        // For each main merchant, add it and its submerchants
        for (Receiver mainMerchant : mainMerchants) {
            // Add main merchant
            result.add(mapToResponse(mainMerchant));
            
            // Add its submerchants (if any)
            List<Receiver> submerchants = submerchantsByParent.get(mainMerchant.getId());
            if (submerchants != null && !submerchants.isEmpty()) {
                // Sort submerchants by company name
                submerchants.sort((a, b) -> {
                    String nameA = a.getCompanyName() != null ? a.getCompanyName() : "";
                    String nameB = b.getCompanyName() != null ? b.getCompanyName() : "";
                    return nameA.compareToIgnoreCase(nameB);
                });
                
                // Add all submerchants
                for (Receiver submerchant : submerchants) {
                    result.add(mapToResponse(submerchant));
                }
            }
        }
        
        // Handle orphaned submerchants (submerchants whose parent is not in the list)
        // This can happen when filtering by status - parent might have different status
        for (java.util.Map.Entry<UUID, List<Receiver>> entry : submerchantsByParent.entrySet()) {
            UUID parentId = entry.getKey();
            boolean parentInList = mainMerchants.stream().anyMatch(m -> m.getId().equals(parentId));
            
            if (!parentInList) {
                // Parent not in list, add submerchants anyway (they'll appear at the end)
                List<Receiver> orphanedSubmerchants = entry.getValue();
                orphanedSubmerchants.sort((a, b) -> {
                    String nameA = a.getCompanyName() != null ? a.getCompanyName() : "";
                    String nameB = b.getCompanyName() != null ? b.getCompanyName() : "";
                    return nameA.compareToIgnoreCase(nameB);
                });
                
                for (Receiver submerchant : orphanedSubmerchants) {
                    result.add(mapToResponse(submerchant));
                }
            }
        }
        
        return result;
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
        
        // Update MoMo account phone if provided
        if (request.getMomoAccountPhone() != null) {
            // Normalize phone number: remove non-digit characters
            String normalizedMomoPhone = request.getMomoAccountPhone().replaceAll("[^0-9]", "");
            receiver.setMomoAccountPhone(normalizedMomoPhone);
        }
        
        // Update flexible mode if provided
        if (request.getIsFlexible() != null) {
            receiver.setIsFlexible(request.getIsFlexible());
            
            // If main merchant is being set to flexible, automatically set all submerchants to flexible as well
            if (request.getIsFlexible() && receiver.getParentReceiver() == null) {
                // This is a main merchant being set to flexible
                List<Receiver> submerchants = receiverRepository.findByParentReceiverId(receiver.getId());
                logger.info("Main merchant '{}' (ID: {}) is being set to flexible. Updating {} submerchants to flexible as well.", 
                    receiver.getCompanyName(), receiver.getId(), submerchants.size());
                
                for (Receiver submerchant : submerchants) {
                    submerchant.setIsFlexible(true);
                    receiverRepository.save(submerchant);
                    logger.info("Submerchant '{}' (ID: {}) set to flexible", 
                        submerchant.getCompanyName(), submerchant.getId());
                }
            }
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
                    // Pay the full requested amount, not just the difference
                    MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
                    moPayRequest.setAmount(newAssignedBalance);
                    moPayRequest.setCurrency("RWF");
                    
                    // Normalize admin phone to 12 digits
                    String normalizedAdminPhone = normalizePhoneTo12Digits(request.getAdminPhone());
                    moPayRequest.setPhone(normalizedAdminPhone);
                    moPayRequest.setPayment_mode("MOBILE");
                    moPayRequest.setMessage("Balance assignment to " + receiver.getCompanyName());
                    
                    // Create transfer to receiver - pay full requested amount
                    MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
                    transfer.setAmount(newAssignedBalance);
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

        // Handle commission: either commissionSettings array (frontend shape) or flat commissionPercentage + commissionPhoneNumber.
        if (request.getCommissionSettings() != null) {
            // Replace receiver's commission settings with the list (same shape as response commissionSettings).
            BigDecimal discountPercentage = request.getDiscountPercentage() != null
                ? request.getDiscountPercentage()
                : (receiver.getDiscountPercentage() != null ? receiver.getDiscountPercentage() : BigDecimal.ZERO);
            BigDecimal userBonusPercentage = request.getUserBonusPercentage() != null
                ? request.getUserBonusPercentage()
                : (receiver.getUserBonusPercentage() != null ? receiver.getUserBonusPercentage() : BigDecimal.ZERO);
            BigDecimal totalCommission = BigDecimal.ZERO;
            for (com.pocketmoney.pocketmoney.dto.CommissionInfo item : request.getCommissionSettings()) {
                if (item == null || item.getCommissionPercentage() == null) continue;
                if (item.getCommissionPercentage().compareTo(BigDecimal.ZERO) < 0 ||
                    item.getCommissionPercentage().compareTo(new BigDecimal("100")) > 0) {
                    throw new RuntimeException("Each commission percentage must be between 0 and 100");
                }
                String phone = item.getPhoneNumber() != null ? item.getPhoneNumber().trim() : "";
                if (phone.isEmpty()) {
                    throw new RuntimeException("Each commission setting must have a phone number");
                }
                totalCommission = totalCommission.add(item.getCommissionPercentage());
            }
            if (discountPercentage.compareTo(BigDecimal.ZERO) > 0
                && userBonusPercentage.add(totalCommission).compareTo(discountPercentage) > 0) {
                throw new RuntimeException(String.format(
                    "The sum of user bonus (%.2f%%) and total commission (%.2f%%) cannot exceed discount (%.2f%%).",
                    userBonusPercentage, totalCommission, discountPercentage));
            }
            List<PaymentCommissionSetting> existing = paymentCommissionSettingRepository.findByReceiverId(receiver.getId());
            java.util.Set<String> newPhones = new java.util.HashSet<>();
            for (com.pocketmoney.pocketmoney.dto.CommissionInfo item : request.getCommissionSettings()) {
                if (item == null || item.getPhoneNumber() == null || item.getPhoneNumber().trim().isEmpty() || item.getCommissionPercentage() == null) continue;
                String normalizedPhone = normalizePhoneTo12Digits(item.getPhoneNumber());
                newPhones.add(normalizedPhone);
                if (paymentCommissionSettingRepository.existsByReceiverIdAndPhoneNumber(receiver.getId(), normalizedPhone)) {
                    paymentCommissionSettingRepository.findByReceiverIdAndPhoneNumber(receiver.getId(), normalizedPhone)
                        .ifPresent(s -> {
                            s.setCommissionPercentage(item.getCommissionPercentage());
                            s.setIsActive(true);
                            paymentCommissionSettingRepository.save(s);
                        });
                } else {
                    PaymentCommissionSetting s = new PaymentCommissionSetting();
                    s.setReceiver(receiver);
                    s.setPhoneNumber(normalizedPhone);
                    s.setCommissionPercentage(item.getCommissionPercentage());
                    s.setIsActive(true);
                    s.setDescription("Created via receiver update");
                    paymentCommissionSettingRepository.save(s);
                }
            }
            for (PaymentCommissionSetting s : existing) {
                if (!newPhones.contains(s.getPhoneNumber())) {
                    s.setIsActive(false);
                    paymentCommissionSettingRepository.save(s);
                }
            }
        } else if (request.getCommissionPercentage() != null || (request.getCommissionPhoneNumber() != null && !request.getCommissionPhoneNumber().trim().isEmpty())) {
            if (request.getCommissionPercentage() == null || request.getCommissionPhoneNumber() == null || request.getCommissionPhoneNumber().trim().isEmpty()) {
                throw new RuntimeException("Both commission percentage and commission phone number must be provided together, or omit both");
            }
            if (request.getCommissionPercentage().compareTo(BigDecimal.ZERO) < 0 ||
                request.getCommissionPercentage().compareTo(new BigDecimal("100")) > 0) {
                throw new RuntimeException("Commission percentage must be between 0 and 100");
            }
            BigDecimal discountPercentage = request.getDiscountPercentage() != null
                ? request.getDiscountPercentage()
                : (receiver.getDiscountPercentage() != null ? receiver.getDiscountPercentage() : BigDecimal.ZERO);
            BigDecimal userBonusPercentage = request.getUserBonusPercentage() != null
                ? request.getUserBonusPercentage()
                : (receiver.getUserBonusPercentage() != null ? receiver.getUserBonusPercentage() : BigDecimal.ZERO);
            BigDecimal totalSplitPercentage = userBonusPercentage.add(request.getCommissionPercentage());
            if (totalSplitPercentage.compareTo(discountPercentage) > 0) {
                throw new RuntimeException(String.format(
                    "The sum of user bonus percentage (%.2f%%) and commission percentage (%.2f%%) cannot exceed discount percentage (%.2f%%). " +
                    "At least some percentage must remain for admin. Current total: %.2f%%",
                    userBonusPercentage, request.getCommissionPercentage(), discountPercentage, totalSplitPercentage));
            }
            String normalizedCommissionPhone = normalizePhoneTo12Digits(request.getCommissionPhoneNumber());
            if (paymentCommissionSettingRepository.existsByReceiverIdAndPhoneNumber(receiver.getId(), normalizedCommissionPhone)) {
                paymentCommissionSettingRepository.findByReceiverIdAndPhoneNumber(receiver.getId(), normalizedCommissionPhone)
                    .ifPresent(existingSetting -> {
                        existingSetting.setCommissionPercentage(request.getCommissionPercentage());
                        existingSetting.setIsActive(true);
                        paymentCommissionSettingRepository.save(existingSetting);
                    });
            } else {
                PaymentCommissionSetting commissionSetting = new PaymentCommissionSetting();
                commissionSetting.setReceiver(receiver);
                commissionSetting.setPhoneNumber(normalizedCommissionPhone);
                commissionSetting.setCommissionPercentage(request.getCommissionPercentage());
                commissionSetting.setIsActive(true);
                commissionSetting.setDescription("Created via receiver update");
                paymentCommissionSettingRepository.save(commissionSetting);
            }
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

        logger.info("=== ASSIGN BALANCE START ===");
        logger.info("Receiver ID: {}", receiverId);
        logger.info("Current assigned balance: {}", currentAssigned);
        logger.info("New assigned balance: {}", newAssignedBalance);
        logger.info("Balance difference: {}", balanceDifference);
        logger.info("Admin phone: {}", request.getAdminPhone());
        logger.info("Receiver phone: {}", request.getReceiverPhone());

        // Validate receiver phone matches
        String normalizedReceiverPhone = normalizePhoneTo12Digits(receiver.getReceiverPhone());
        String normalizedRequestReceiverPhone = normalizePhoneTo12Digits(request.getReceiverPhone());
        if (!normalizedReceiverPhone.equals(normalizedRequestReceiverPhone)) {
            throw new RuntimeException("Receiver phone number does not match. Expected: " + receiver.getReceiverPhone() + ", Provided: " + request.getReceiverPhone());
        }

        // Allow multiple assignment attempts - each call creates a new history entry
        // This allows retries if payment fails

        // Always initiate MoPay payment with the full assigned amount (regardless of increase or decrease)
        logger.info("=== INITIATING PAYMENT ===");
        logger.info("Will initiate payment for amount: {}", newAssignedBalance);
        
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

        // Handle commission percentage and phone number if provided (both must be provided together, or omit both)
        if (request.getCommissionPercentage() != null || (request.getCommissionPhoneNumber() != null && !request.getCommissionPhoneNumber().trim().isEmpty())) {
            // If one is provided, both must be provided
            if (request.getCommissionPercentage() == null || request.getCommissionPhoneNumber() == null || request.getCommissionPhoneNumber().trim().isEmpty()) {
                throw new RuntimeException("Both commission percentage and commission phone number must be provided together, or omit both");
            }
            if (request.getCommissionPercentage().compareTo(BigDecimal.ZERO) < 0 ||
                request.getCommissionPercentage().compareTo(new BigDecimal("100")) > 0) {
                throw new RuntimeException("Commission percentage must be between 0 and 100");
            }
            
            // Get discount and user bonus percentages (use request values if provided, otherwise use receiver's current values)
            BigDecimal discountPercentage = request.getDiscountPercentage() != null 
                ? request.getDiscountPercentage() 
                : (receiver.getDiscountPercentage() != null ? receiver.getDiscountPercentage() : BigDecimal.ZERO);
            BigDecimal userBonusPercentage = request.getUserBonusPercentage() != null 
                ? request.getUserBonusPercentage() 
                : (receiver.getUserBonusPercentage() != null ? receiver.getUserBonusPercentage() : BigDecimal.ZERO);
            
            // Validate that userBonusPercentage + commissionPercentage doesn't exceed discountPercentage
            // This ensures admin gets at least some percentage: discountPercentage - userBonusPercentage - commissionPercentage >= 0
            BigDecimal totalSplitPercentage = userBonusPercentage.add(request.getCommissionPercentage());
            if (totalSplitPercentage.compareTo(discountPercentage) > 0) {
                throw new RuntimeException(String.format(
                    "The sum of user bonus percentage (%.2f%%) and commission percentage (%.2f%%) cannot exceed discount percentage (%.2f%%). " +
                    "At least some percentage must remain for admin. Current total: %.2f%%",
                    userBonusPercentage, request.getCommissionPercentage(), discountPercentage, totalSplitPercentage));
            }
            
            // Normalize commission phone number
            String normalizedCommissionPhone = normalizePhoneTo12Digits(request.getCommissionPhoneNumber());
            
            // Check if commission setting already exists for this receiver and phone number
            if (paymentCommissionSettingRepository.existsByReceiverIdAndPhoneNumber(receiver.getId(), normalizedCommissionPhone)) {
                // Update existing commission setting
                paymentCommissionSettingRepository.findByReceiverIdAndPhoneNumber(receiver.getId(), normalizedCommissionPhone)
                    .ifPresent(existingSetting -> {
                        existingSetting.setCommissionPercentage(request.getCommissionPercentage());
                        existingSetting.setIsActive(true);
                        paymentCommissionSettingRepository.save(existingSetting);
                    });
            } else {
                // Create new commission setting
                PaymentCommissionSetting commissionSetting = new PaymentCommissionSetting();
                commissionSetting.setReceiver(receiver);
                commissionSetting.setPhoneNumber(normalizedCommissionPhone);
                commissionSetting.setCommissionPercentage(request.getCommissionPercentage());
                commissionSetting.setIsActive(true);
                commissionSetting.setDescription("Created via balance assignment");
                paymentCommissionSettingRepository.save(commissionSetting);
            }
        }

        // Initiate MoPay payment from admin to receiver
        // Pay the full requested amount, not just the difference
        logger.info("=== PREPARING MOPAY REQUEST ===");
        logger.info("Payment amount: {}", newAssignedBalance);
        logger.info("Current assigned balance: {}", currentAssigned);
        logger.info("Balance difference: {}", balanceDifference);
        
        MoPayInitiateRequest moPayRequest = new MoPayInitiateRequest();
        moPayRequest.setAmount(newAssignedBalance);  // FULL AMOUNT, not balanceDifference
        moPayRequest.setCurrency("RWF");
        
        // Normalize admin phone to 12 digits
        String normalizedAdminPhone = normalizePhoneTo12Digits(request.getAdminPhone());
        logger.info("Normalized admin phone: {}", normalizedAdminPhone);
        moPayRequest.setPhone(normalizedAdminPhone);
        moPayRequest.setPayment_mode("MOBILE");
        moPayRequest.setMessage("Balance assignment to " + receiver.getCompanyName());
        
        // Create transfer to receiver - pay full requested amount
        MoPayInitiateRequest.Transfer transfer = new MoPayInitiateRequest.Transfer();
        transfer.setAmount(newAssignedBalance);  // FULL AMOUNT, not balanceDifference
        transfer.setPhone(Long.parseLong(normalizedReceiverPhone));
        transfer.setMessage("Balance assignment from admin");
        moPayRequest.setTransfers(java.util.List.of(transfer));
        
        logger.info("=== MOPAY REQUEST DETAILS ===");
        logger.info("MoPay request amount: {}", moPayRequest.getAmount());
        logger.info("Transfer amount: {}", transfer.getAmount());
        logger.info("Admin phone (Long): {}", moPayRequest.getPhone());
        logger.info("Receiver phone (Long): {}", transfer.getPhone());
        
        // Initiate payment with MoPay
        logger.info("=== CALLING MOPAY SERVICE ===");
        MoPayResponse moPayResponse = moPayService.initiatePayment(moPayRequest);
        logger.info("=== MOPAY RESPONSE RECEIVED ===");
        logger.info("MoPay response status: {}", moPayResponse != null ? moPayResponse.getStatus() : "NULL");
        logger.info("MoPay transaction ID: {}", moPayResponse != null ? moPayResponse.getTransactionId() : "NULL");
        logger.info("MoPay message: {}", moPayResponse != null ? moPayResponse.getMessage() : "NULL");
        logger.info("MoPay success: {}", moPayResponse != null ? moPayResponse.getSuccess() : "NULL");
        
        // Create balance assignment history
        BalanceAssignmentHistory history = new BalanceAssignmentHistory();
        history.setReceiver(receiver);
        history.setAssignedBalance(newAssignedBalance);
        history.setPreviousAssignedBalance(currentAssigned);
        history.setBalanceDifference(balanceDifference);
        history.setAssignedBy("ADMIN"); // TODO: Get from authentication context
        history.setAdminPhone(request.getAdminPhone()); // Store admin phone for SMS notifications
        history.setNotes(request.getNotes() != null ? request.getNotes() : "Balance assignment via MoPay payment");
        history.setStatus(BalanceAssignmentStatus.PENDING);
        
        // Check MoPay response
        String transactionId = moPayResponse != null ? moPayResponse.getTransactionId() : null;
        // Check if payment was successfully initiated
        // Success criteria: HTTP status 201 (CREATED) OR transactionId is present
        boolean paymentInitiated = false;
        if (moPayResponse != null) {
            Integer httpStatus = moPayResponse.getStatus();
            // Check for HTTP 201 (CREATED) or presence of transactionId
            if ((httpStatus != null && httpStatus == 201) || transactionId != null) {
                paymentInitiated = true;
            }
        }
        
        logger.info("=== PROCESSING MOPAY RESPONSE ===");
        logger.info("Payment initiated flag: {}", paymentInitiated);
        logger.info("Transaction ID present: {}", transactionId != null);
        
        if (paymentInitiated && transactionId != null) {
            // Successfully initiated - generate custom transaction ID starting with MOPAY
            String customTransactionId = generateMopayTransactionId();
            history.setMopayTransactionId(customTransactionId);
            logger.info("✅ MoPay payment initiated successfully!");
            logger.info("   MoPay Transaction ID: {}", transactionId);
            logger.info("   Custom Transaction ID: {}", customTransactionId);
            logger.info("   Payment amount: {}", newAssignedBalance);
            logger.info("   Balance difference: {}", balanceDifference);
        } else {
            // Initiation failed
            history.setStatus(BalanceAssignmentStatus.REJECTED);
            String errorMessage = moPayResponse != null && moPayResponse.getMessage() != null 
                ? moPayResponse.getMessage() 
                : "MoPay payment initiation failed";
            Integer httpStatus = moPayResponse != null ? moPayResponse.getStatus() : null;
            logger.error("❌ MoPay payment initiation failed!");
            logger.error("   HTTP Status: {}", httpStatus);
            logger.error("   Transaction ID: {}", transactionId);
            logger.error("   Error: {}", errorMessage);
            logger.error("   Payment initiated flag: {}", paymentInitiated);
            history.setNotes("Balance assignment failed: " + errorMessage);
        }
        
        // Save receiver updates (discount/bonus percentages)
        logger.info("=== SAVING RECEIVER AND HISTORY ===");
        receiverRepository.save(receiver);
        
        // Save balance assignment history
        BalanceAssignmentHistory savedHistory = balanceAssignmentHistoryRepository.save(history);
        logger.info("=== ASSIGN BALANCE COMPLETE ===");
        logger.info("History ID: {}", savedHistory.getId());
        logger.info("History status: {}", savedHistory.getStatus());
        logger.info("Assigned balance in history: {}", savedHistory.getAssignedBalance());
        logger.info("Balance difference in history: {}", savedHistory.getBalanceDifference());
        logger.info("Payment amount should be: {}", savedHistory.getAssignedBalance());
        
        // Send SMS and WhatsApp notification to receiver about balance assignment
        if (paymentInitiated && transactionId != null) {
            try {
                String receiverPhoneNormalized = normalizePhoneTo12Digits(receiver.getReceiverPhone());
                String smsMessage = String.format("Balance of %s RWF assigned. Please approve or reject in your account.", 
                    newAssignedBalance.toPlainString());
                String whatsAppMessage = String.format("[Besoft Group]: Assigned %s RWF to your Balance via Momo.\n\nPlease Confirm by Approving", 
                    newAssignedBalance.toPlainString());
                messagingService.sendSms(smsMessage, receiverPhoneNormalized);
                whatsAppService.sendWhatsApp(whatsAppMessage, receiverPhoneNormalized);
                logger.info("SMS and WhatsApp sent to receiver {} about balance assignment", receiverPhoneNormalized);
            } catch (Exception e) {
                logger.error("Failed to send SMS/WhatsApp to receiver: ", e);
                // Don't fail the balance assignment if SMS/WhatsApp fails
            }
        }
        
        BalanceAssignmentHistoryResponse response = mapToBalanceAssignmentHistoryResponse(savedHistory);
        logger.info("Response payment amount: {}", response.getPaymentAmount());
        logger.info("Response assigned balance: {}", response.getAssignedBalance());
        return response;
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
        // Check if main merchant (no parent receiver)
        response.setIsMainMerchant(receiver.getParentReceiver() == null);
        response.setIsFlexible(receiver.getIsFlexible() != null ? receiver.getIsFlexible() : false);
        response.setLastTransactionDate(receiver.getLastTransactionDate());
        response.setCreatedAt(receiver.getCreatedAt());
        response.setUpdatedAt(receiver.getUpdatedAt());

        return response;
    }

    @Transactional(readOnly = true)
    public ReceiverWalletResponse getReceiverWallet(UUID id) {
        Receiver receiver = receiverRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + id));

        // Each receiver (main merchant or submerchant) has its own separate balance
        // Use receiver's own balance, not parent's

        // Get commission settings for this receiver
        List<PaymentCommissionSetting> commissionSettings = paymentCommissionSettingRepository.findByReceiverIdAndIsActiveTrue(id);
        List<com.pocketmoney.pocketmoney.dto.CommissionInfo> commissionInfoList = commissionSettings.stream()
                .map(setting -> new com.pocketmoney.pocketmoney.dto.CommissionInfo(
                        setting.getPhoneNumber(),
                        setting.getCommissionPercentage()
                ))
                .collect(Collectors.toList());

        ReceiverWalletResponse response = new ReceiverWalletResponse();
        response.setReceiverId(receiver.getId());
        response.setCompanyName(receiver.getCompanyName());
        response.setReceiverPhone(receiver.getReceiverPhone());
        response.setWalletBalance(receiver.getWalletBalance()); // Receiver's own wallet balance
        response.setTotalReceived(receiver.getTotalReceived()); // Receiver's own total received
        response.setAssignedBalance(receiver.getAssignedBalance()); // Receiver's own assigned balance
        response.setRemainingBalance(receiver.getRemainingBalance()); // Receiver's own remaining balance
        response.setDiscountPercentage(receiver.getDiscountPercentage()); // Use receiver's own percentages
        response.setUserBonusPercentage(receiver.getUserBonusPercentage()); // Use receiver's own percentages
        response.setCommissionSettings(commissionInfoList);
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

        // Calculate metrics using dynamic queries to avoid parameter type issues
        BigDecimal totalPaid = sumSuccessfulAmountByReceiverAndFilters(receiverId, fromDate, toDate, categoryId);
        response.setTotalPaid(totalPaid != null ? totalPaid : BigDecimal.ZERO);

        Long totalTransactions = countAllTransactionsByReceiverAndFilters(receiverId, fromDate, toDate, categoryId);
        response.setTotalTransactions(totalTransactions != null ? totalTransactions : 0L);

        Long approvedTransactions = countSuccessfulTransactionsByReceiverAndFilters(receiverId, fromDate, toDate, categoryId);
        response.setApprovedTransactions(approvedTransactions != null ? approvedTransactions : 0L);

        Long totalUsers = countDistinctUsersByReceiverAndDateRange(receiverId, fromDate, toDate);
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
            List<Object[]> categoryBreakdown = getCategoryBreakdownByReceiver(receiverId, fromDate, toDate);
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

        // Get recent transactions (5 most recent) - filter by date range and category if provided
        // Create final copies for lambda usage
        final LocalDateTime finalFromDate = fromDate;
        final LocalDateTime finalToDate = toDate;
        final UUID finalCategoryId = categoryId;
        
        List<Transaction> recentTransactions = transactionRepository.findByReceiverOrderByCreatedAtDescWithUser(receiver)
                .stream()
                .filter(t -> {
                    // Apply date range filter if provided
                    if (finalFromDate != null && t.getCreatedAt().isBefore(finalFromDate)) {
                        return false;
                    }
                    if (finalToDate != null && t.getCreatedAt().isAfter(finalToDate)) {
                        return false;
                    }
                    // Apply category filter if provided
                    if (finalCategoryId != null && (t.getPaymentCategory() == null || !t.getPaymentCategory().getId().equals(finalCategoryId))) {
                        return false;
                    }
                    return true;
                })
                .limit(5)
                .collect(Collectors.toList());
        
        response.setRecentTransactions(recentTransactions.stream()
                .map(t -> mapToAnalyticsRecentTransaction(t, receiverId))
                .collect(Collectors.toList()));

        return response;
    }

    private ReceiverAnalyticsResponse.RecentTransaction mapToAnalyticsRecentTransaction(Transaction transaction, UUID receiverId) {
        ReceiverAnalyticsResponse.RecentTransaction recent = new ReceiverAnalyticsResponse.RecentTransaction();
        recent.setTransactionId(transaction.getId());
        recent.setMopayTransactionId(transaction.getMopayTransactionId()); // POCHI transaction ID
        
        // Handle null user for guest MOMO payments
        if (transaction.getUser() != null) {
            recent.setUserId(transaction.getUser().getId());
            recent.setUserName(transaction.getUser().getFullNames());
            recent.setUserPhone(transaction.getUser().getPhoneNumber());
        } else {
            // Guest payment - use phone number from transaction if available
            recent.setUserId(null);
            recent.setUserName("Guest User");
            recent.setUserPhone(transaction.getPhoneNumber()); // Use phone number stored in transaction for MOMO payments
        }
        
        recent.setAmount(transaction.getAmount());
        recent.setDiscountAmount(transaction.getDiscountAmount());
        recent.setUserBonusAmount(transaction.getUserBonusAmount());
        recent.setStatus(transaction.getStatus() != null ? transaction.getStatus().name() : null);
        if (transaction.getPaymentCategory() != null) {
            recent.setPaymentCategoryName(transaction.getPaymentCategory().getName());
        }
        recent.setCreatedAt(transaction.getCreatedAt());
        
        // Add receiver information
        if (transaction.getReceiver() != null) {
            recent.setReceiverId(transaction.getReceiver().getId());
            recent.setReceiverCompanyName(transaction.getReceiver().getCompanyName());
            // Check if this transaction was made by a submerchant (not the main receiver)
            recent.setIsSubmerchant(!transaction.getReceiver().getId().equals(receiverId));
        }
        
        return recent;
    }

    private ReceiverResponse mapToResponse(Receiver receiver) {
        return mapToResponse(receiver, true);
    }
    
    private ReceiverResponse mapToResponse(Receiver receiver, boolean loadSubmerchantCount) {
        ReceiverResponse response = new ReceiverResponse();
        response.setId(receiver.getId());
        response.setCompanyName(receiver.getCompanyName());
        response.setManagerName(receiver.getManagerName());
        response.setUsername(receiver.getUsername());
        response.setReceiverPhone(receiver.getReceiverPhone());
        response.setMomoAccountPhone(receiver.getMomoAccountPhone()); // MoMo account phone (if configured)
        response.setAccountNumber(receiver.getAccountNumber());
        response.setStatus(receiver.getStatus());
        response.setEmail(receiver.getEmail());
        response.setAddress(receiver.getAddress());
        response.setDescription(receiver.getDescription());
        
        // Check if receiver has parent (is submerchant) - access parent to trigger lazy load if needed
        Receiver parentReceiver = receiver.getParentReceiver();
        boolean isSubmerchant = parentReceiver != null;
        
        // Use shared balance if submerchant (parent's balance), otherwise use own balance
        Receiver balanceOwner = isSubmerchant ? parentReceiver : receiver;
        response.setWalletBalance(balanceOwner.getWalletBalance()); // Shared wallet balance
        response.setTotalReceived(balanceOwner.getTotalReceived()); // Shared total received
        response.setAssignedBalance(balanceOwner.getAssignedBalance()); // Shared assigned balance
        response.setRemainingBalance(balanceOwner.getRemainingBalance()); // Shared remaining balance
        response.setDiscountPercentage(balanceOwner.getDiscountPercentage()); // Use balance owner's percentages
        response.setUserBonusPercentage(balanceOwner.getUserBonusPercentage()); // Use balance owner's percentages
        response.setIsFlexible(receiver.getIsFlexible() != null ? receiver.getIsFlexible() : false); // Flexible mode flag
        
        // Get commission settings for this receiver
        List<PaymentCommissionSetting> commissionSettings = paymentCommissionSettingRepository.findByReceiverIdAndIsActiveTrue(receiver.getId());
        List<com.pocketmoney.pocketmoney.dto.CommissionInfo> commissionInfoList = commissionSettings.stream()
                .map(setting -> new com.pocketmoney.pocketmoney.dto.CommissionInfo(
                        setting.getPhoneNumber(),
                        setting.getCommissionPercentage()
                ))
                .collect(Collectors.toList());
        response.setCommissionSettings(commissionInfoList);
        
        response.setPendingBalanceAssignments(0); // Default, will be set in getReceiverById if needed
        
        // Submerchant relationship info
        if (isSubmerchant) {
            response.setParentReceiverId(parentReceiver.getId());
            response.setParentReceiverCompanyName(parentReceiver.getCompanyName());
            response.setIsMainMerchant(false);
            response.setSubmerchantCount(0); // Submerchants don't have submerchants
        } else {
            response.setParentReceiverId(null);
            response.setParentReceiverCompanyName(null);
            response.setIsMainMerchant(true);
            // Only count submerchants if requested (skip for newly created submerchants)
            if (loadSubmerchantCount) {
                // Use count query instead of fetching all submerchants for better performance
                long submerchantCount = receiverRepository.countByParentReceiverId(receiver.getId());
                response.setSubmerchantCount((int) submerchantCount);
            } else {
                response.setSubmerchantCount(0); // Default for new receivers
            }
        }
        
        response.setLastTransactionDate(receiver.getLastTransactionDate());
        
        // Get last transaction ID for this receiver
        // Query the most recent transaction for this receiver (limit to 1 for efficiency)
        List<Transaction> lastTransactions = transactionRepository.findByReceiverOrderByCreatedAtDescWithUser(receiver)
                .stream()
                .limit(1)
                .collect(Collectors.toList());
        if (!lastTransactions.isEmpty()) {
            response.setLastTransactionId(lastTransactions.get(0).getId());
        } else {
            response.setLastTransactionId(null);
        }
        
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
        logger.info("=== MAPPING BALANCE ASSIGNMENT HISTORY ===");
        logger.info("History ID: {}", history.getId());
        logger.info("History assignedBalance: {}", history.getAssignedBalance());
        logger.info("History balanceDifference: {}", history.getBalanceDifference());
        
        BalanceAssignmentHistoryResponse response = new BalanceAssignmentHistoryResponse();
        response.setId(history.getId());
        response.setReceiverId(history.getReceiver().getId());
        response.setReceiverCompanyName(history.getReceiver().getCompanyName());
        response.setAssignedBalance(history.getAssignedBalance());
        response.setPreviousAssignedBalance(history.getPreviousAssignedBalance());
        response.setBalanceDifference(history.getBalanceDifference());
        
        // Payment amount is always the full assigned balance (regardless of increase or decrease)
        BigDecimal paymentAmount = history.getAssignedBalance();
        logger.info("Calculated paymentAmount from assignedBalance: {}", paymentAmount);
        
        response.setPaymentAmount(paymentAmount);
        
        // Verify it was set correctly
        BigDecimal verifyPaymentAmount = response.getPaymentAmount();
        logger.info("After setting, response.getPaymentAmount() returns: {}", verifyPaymentAmount);
        logger.info("Payment amount is null? {}", verifyPaymentAmount == null);
        if (verifyPaymentAmount != null) {
            logger.info("Payment amount compareTo zero: {}", verifyPaymentAmount.compareTo(BigDecimal.ZERO));
        }
        
        response.setAssignedBy(history.getAssignedBy());
        response.setNotes(history.getNotes());
        response.setStatus(history.getStatus());
        response.setApprovedBy(history.getApprovedBy());
        response.setApprovedAt(history.getApprovedAt());
        response.setMopayTransactionId(history.getMopayTransactionId());
        response.setCreatedAt(history.getCreatedAt());
        
        // Final verification before returning
        logger.info("Final response paymentAmount before return: {}", response.getPaymentAmount());
        logger.info("Final response assignedBalance before return: {}", response.getAssignedBalance());
        
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
            BigDecimal sentAmount = history.getAssignedBalance(); // Amount that was sent/paid
            BigDecimal currentRemaining = balanceOwner.getRemainingBalance();
            BigDecimal currentWallet = balanceOwner.getWalletBalance() != null ? balanceOwner.getWalletBalance() : BigDecimal.ZERO;
            
            // Calculate discount amount based on discount percentage (discount is calculated on the sent amount)
            BigDecimal discountPercentage = balanceOwner.getDiscountPercentage() != null 
                ? balanceOwner.getDiscountPercentage() 
                : BigDecimal.ZERO;
            BigDecimal discountAmount = sentAmount
                .multiply(discountPercentage)
                .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
            
            logger.info("=== APPROVAL CALCULATION ===");
            logger.info("Current assigned: {}, Sent amount: {}, Discount %: {}", 
                currentAssigned, sentAmount, discountPercentage);
            logger.info("Discount amount: {} ({}% of {})", discountAmount, discountPercentage, sentAmount);
            logger.info("Current remaining: {}", currentRemaining);
            logger.info("Current wallet: {}", currentWallet);
            
            // Add the sent amount + discount amount to previous assigned balance
            BigDecimal updatedAssignedBalance = currentAssigned.add(sentAmount).add(discountAmount);
            logger.info("Updated assigned balance: {} + {} + {} = {}", 
                currentAssigned, sentAmount, discountAmount, updatedAssignedBalance);
            
            // Add sent amount + discount amount to remaining balance
            BigDecimal newRemainingBalance = currentRemaining.add(sentAmount).add(discountAmount);
            logger.info("New remaining balance: {} + {} + {} = {}", 
                currentRemaining, sentAmount, discountAmount, newRemainingBalance);
            
            // Ensure remaining balance never goes below 0
            if (newRemainingBalance.compareTo(BigDecimal.ZERO) < 0) {
                newRemainingBalance = BigDecimal.ZERO;
                logger.info("Remaining balance would have been negative, setting to 0");
            }
            
            // Wallet balance = Previous wallet balance + Sent amount + Discount amount
            BigDecimal newWalletBalance = currentWallet.add(sentAmount).add(discountAmount);
            logger.info("New wallet balance: {} + {} + {} = {}", 
                currentWallet, sentAmount, discountAmount, newWalletBalance);
            
            balanceOwner.setAssignedBalance(updatedAssignedBalance);
            balanceOwner.setRemainingBalance(newRemainingBalance);
            balanceOwner.setWalletBalance(newWalletBalance);
            
            // Update balance owner (shared balance) - assigned balance is already updated above
            receiverRepository.save(balanceOwner);
            
            // Also update receiver's assigned balance for tracking
            receiver.setAssignedBalance(balanceOwner.getAssignedBalance());
            if (receiver.getParentReceiver() == null) {
                // Only update wallet for main receiver (not submerchants, as wallet is shared)
                receiver.setWalletBalance(balanceOwner.getWalletBalance());
            }
            receiverRepository.save(receiver);
            
            logger.info("Balance assignment approved - Receiver: {}, Assigned: {} (was {}, added {} + discount {}), Wallet: {} (was {}, added {} + discount {}), Remaining: {} (was {}, added {} + discount {})", 
                    receiverId, updatedAssignedBalance, currentAssigned, sentAmount, discountAmount,
                    newWalletBalance, currentWallet, sentAmount, discountAmount,
                    newRemainingBalance, currentRemaining, sentAmount, discountAmount);
            
            // Send SMS and WhatsApp notification to admin when receiver approves
            String adminPhone = history.getAdminPhone();
            if (adminPhone != null && !adminPhone.trim().isEmpty()) {
                try {
                    String adminPhoneNormalized = normalizePhoneTo12Digits(adminPhone);
                    String smsMessage = String.format("Balance of %s RWF approved and received by %s", 
                        sentAmount.toPlainString(), receiver.getCompanyName());
                    String whatsAppMessage = String.format("[%s]: Approved %s RWF.", 
                        receiver.getCompanyName(), sentAmount.toPlainString());
                    messagingService.sendSms(smsMessage, adminPhoneNormalized);
                    whatsAppService.sendWhatsApp(whatsAppMessage, adminPhoneNormalized);
                    logger.info("SMS and WhatsApp sent to admin {} about balance approval", adminPhoneNormalized);
                } catch (Exception e) {
                    logger.error("Failed to send SMS/WhatsApp to admin: ", e);
                    // Don't fail the approval if SMS/WhatsApp fails
                }
            }
        } else {
            history.setStatus(BalanceAssignmentStatus.REJECTED);
            history.setApprovedBy(receiver.getUsername());
            history.setApprovedAt(LocalDateTime.now());
            
            // Send SMS and WhatsApp notification to admin when receiver rejects
            String adminPhone = history.getAdminPhone();
            if (adminPhone != null && !adminPhone.trim().isEmpty()) {
                try {
                    String adminPhoneNormalized = normalizePhoneTo12Digits(adminPhone);
                    String message = String.format("Balance of %s RWF rejected by %s", 
                        history.getAssignedBalance().toPlainString(), receiver.getCompanyName());
                    messagingService.sendSms(message, adminPhoneNormalized);
                    whatsAppService.sendWhatsApp(message, adminPhoneNormalized);
                    logger.info("SMS and WhatsApp sent to admin {} about balance rejection", adminPhoneNormalized);
                } catch (Exception e) {
                    logger.error("Failed to send SMS/WhatsApp to admin: ", e);
                    // Don't fail the rejection if SMS/WhatsApp fails
                }
            }
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
        
        // Check if balance owner is in flexible mode
        boolean isFlexible = balanceOwner.getIsFlexible() != null && balanceOwner.getIsFlexible();
        
        if (isFlexible) {
            // FLEXIBLE MODE: Calculate walletBalance as total topped up amount
            // Sum all totalToppedUp from MerchantUserBalance for this receiver (and submerchants if main merchant)
            BigDecimal totalToppedUp = BigDecimal.ZERO;
            
            // Get all MerchantUserBalance records for this receiver
            List<MerchantUserBalance> merchantBalances = merchantUserBalanceRepository.findByReceiverId(balanceOwner.getId());
            for (MerchantUserBalance mb : merchantBalances) {
                totalToppedUp = totalToppedUp.add(mb.getTotalToppedUp() != null ? mb.getTotalToppedUp() : BigDecimal.ZERO);
            }
            
            // If main merchant, also include submerchants' topped up amounts
            if (receiver.getParentReceiver() == null) {
                List<Receiver> submerchants = receiverRepository.findByParentReceiverId(receiverId);
                for (Receiver submerchant : submerchants) {
                    List<MerchantUserBalance> submerchantBalances = merchantUserBalanceRepository.findByReceiverId(submerchant.getId());
                    for (MerchantUserBalance mb : submerchantBalances) {
                        totalToppedUp = totalToppedUp.add(mb.getTotalToppedUp() != null ? mb.getTotalToppedUp() : BigDecimal.ZERO);
                    }
                }
            }
            
            response.setWalletBalance(totalToppedUp);
            logger.info("Flexible mode: walletBalance set to total topped up: {}", totalToppedUp);
        } else {
            // NON-FLEXIBLE MODE: Use existing wallet balance
            response.setWalletBalance(balanceOwner.getWalletBalance());
        }
        
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
            
            // In flexible mode, totalReceived and totalRevenue are the same (sum of payments)
            if (isFlexible) {
                response.setTotalReceived(totalRevenue != null ? totalRevenue : BigDecimal.ZERO);
                response.setTotalRevenue(totalRevenue != null ? totalRevenue : BigDecimal.ZERO);
                logger.info("Flexible mode (main merchant): totalReceived and totalRevenue set to sum of payments: {}", totalRevenue);
            } else {
                response.setTotalReceived(balanceOwner.getTotalReceived());
                response.setTotalRevenue(totalRevenue != null ? totalRevenue : BigDecimal.ZERO);
            }
            
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
            
            // In flexible mode, totalReceived and totalRevenue are the same (sum of payments)
            if (isFlexible) {
                response.setTotalReceived(totalRevenue != null ? totalRevenue : BigDecimal.ZERO);
                response.setTotalRevenue(totalRevenue != null ? totalRevenue : BigDecimal.ZERO);
                logger.info("Flexible mode (submerchant): totalReceived and totalRevenue set to sum of payments: {}", totalRevenue);
            } else {
                response.setTotalReceived(balanceOwner.getTotalReceived());
                response.setTotalRevenue(totalRevenue != null ? totalRevenue : BigDecimal.ZERO);
            }
            
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
        boolean isFlexible = mainReceiver.getIsFlexible() != null && mainReceiver.getIsFlexible();
        
        BigDecimal mainWalletBalance;
        BigDecimal mainRemainingBalance = mainReceiver.getRemainingBalance();
        
        if (isFlexible) {
            // FLEXIBLE MODE: Calculate walletBalance as total topped up amount
            BigDecimal totalToppedUp = BigDecimal.ZERO;
            List<MerchantUserBalance> merchantBalances = merchantUserBalanceRepository.findByReceiverId(mainReceiverId);
            for (MerchantUserBalance mb : merchantBalances) {
                totalToppedUp = totalToppedUp.add(mb.getTotalToppedUp() != null ? mb.getTotalToppedUp() : BigDecimal.ZERO);
            }
            mainWalletBalance = totalToppedUp;
        } else {
            mainWalletBalance = mainReceiver.getWalletBalance();
        }
        
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
            
            // For flexible mode, calculate submerchant wallet balance as total topped up
            BigDecimal subWalletBalance;
            if (isFlexible) {
                BigDecimal subTotalToppedUp = BigDecimal.ZERO;
                List<MerchantUserBalance> subMerchantBalances = merchantUserBalanceRepository.findByReceiverId(submerchant.getId());
                for (MerchantUserBalance mb : subMerchantBalances) {
                    subTotalToppedUp = subTotalToppedUp.add(mb.getTotalToppedUp() != null ? mb.getTotalToppedUp() : BigDecimal.ZERO);
                }
                subWalletBalance = subTotalToppedUp;
            } else {
                subWalletBalance = submerchant.getWalletBalance();
            }
            
            combinedWalletBalance = combinedWalletBalance.add(subWalletBalance);
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
        recent.setMopayTransactionId(transaction.getMopayTransactionId()); // POCHI transaction ID
        
        // Handle null user for guest MOMO payments
        if (transaction.getUser() != null) {
            recent.setUserId(transaction.getUser().getId());
            recent.setUserName(transaction.getUser().getFullNames());
            recent.setUserPhone(transaction.getUser().getPhoneNumber());
        } else {
            // Guest payment - use phone number from transaction if available
            recent.setUserId(null);
            recent.setUserName("Guest User");
            recent.setUserPhone(transaction.getPhoneNumber()); // Use phone number stored in transaction for MOMO payments
        }
        
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
        // Verify main receiver exists and is a main merchant (has no parent) - use entityManager for better control
        Receiver mainReceiver = receiverRepository.findById(mainReceiverId)
                .orElseThrow(() -> new RuntimeException("Main receiver not found with id: " + mainReceiverId));
        
        // Check if main receiver has parent (is already a submerchant) - access parent to trigger lazy load
        if (mainReceiver.getParentReceiver() != null) {
            throw new RuntimeException("Cannot create submerchant. The specified receiver is already a submerchant.");
        }
        
        // Normalize email: convert empty/blank strings to null (do this before validation)
        String email = (request.getEmail() != null && !request.getEmail().trim().isEmpty()) 
                ? request.getEmail().trim() 
                : null;

        // Validate email format if provided
        if (email != null) {
            String emailRegex = "^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$";
            if (!email.matches(emailRegex)) {
                throw new RuntimeException("Invalid email format");
            }
        }
        
        // Batch existence checks - these are fast index lookups, but we do them in parallel conceptually
        // Check if username already exists
        if (receiverRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already exists: " + request.getUsername());
        }

        // Check if phone number already exists
        if (receiverRepository.existsByReceiverPhone(request.getReceiverPhone())) {
            throw new RuntimeException("Receiver phone number already exists: " + request.getReceiverPhone());
        }

        // Check if email already exists (only if email is provided)
        if (email != null && receiverRepository.existsByEmail(email)) {
            throw new RuntimeException("Email already exists: " + email);
        }

        // Create new receiver - set all fields at once
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
        
        // Set parent receiver (link as submerchant) - use the already loaded mainReceiver
        submerchant.setParentReceiver(mainReceiver);
        
        // Inherit flexible status from main merchant
        // If main merchant is flexible, submerchant should also be flexible
        boolean mainMerchantIsFlexible = mainReceiver.getIsFlexible() != null && mainReceiver.getIsFlexible();
        submerchant.setIsFlexible(mainMerchantIsFlexible);
        if (mainMerchantIsFlexible) {
            logger.info("New submerchant '{}' inheriting flexible status from main merchant '{}'", 
                submerchant.getCompanyName(), mainReceiver.getCompanyName());
        }

        // Save submerchant
        Receiver savedSubmerchant = receiverRepository.save(submerchant);
        
        // Flush to ensure the entity is persisted before mapping
        receiverRepository.flush();
        
        // Ensure parent is set in the persisted entity (Hibernate should maintain this, but we ensure it)
        savedSubmerchant.setParentReceiver(mainReceiver);
        
        // Map to response - optimized for submerchants (no submerchant count query needed)
        // Pass false to skip submerchant count since we know it's a new submerchant (always 0)
        return mapToResponse(savedSubmerchant, false);
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
        
        // Inherit flexible status from main merchant
        // If main merchant is flexible, submerchant should also be flexible
        boolean mainMerchantIsFlexible = mainReceiver.getIsFlexible() != null && mainReceiver.getIsFlexible();
        if (mainMerchantIsFlexible) {
            submerchant.setIsFlexible(true);
            logger.info("Linked submerchant '{}' inheriting flexible status from main merchant '{}'", 
                submerchant.getCompanyName(), mainReceiver.getCompanyName());
        }
        
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
        
        // Get all submerchants (both active and suspended/unlinked)
        // This includes active submerchants and suspended ones that still have the parent relationship
        List<Receiver> allSubmerchants = receiverRepository.findByParentReceiverId(mainReceiverId);
        
        // Map to responses
        List<ReceiverResponse> submerchantResponses = allSubmerchants.stream()
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
        
        // IMPORTANT: Instead of setting parentReceiver to null, we keep it for historical tracking
        // This allows us to still see unlinked submerchants in the submerchants list
        // Only change status to SUSPENDED - keep the parent relationship for record-keeping
        submerchant.setStatus(ReceiverStatus.SUSPENDED);
        
        // Save the submerchant (with parent still set, just status changed)
        Receiver savedSubmerchant = receiverRepository.save(submerchant);
        
        return mapToResponse(savedSubmerchant);
    }

    @Transactional
    public ReceiverResponse linkSubmerchantAgain(UUID mainReceiverId, UUID submerchantId) {
        // Verify main receiver exists and is a main merchant (has no parent)
        Receiver mainReceiver = receiverRepository.findById(mainReceiverId)
                .orElseThrow(() -> new RuntimeException("Main receiver not found with id: " + mainReceiverId));
        
        if (mainReceiver.getParentReceiver() != null) {
            throw new RuntimeException("Cannot link submerchant. The specified receiver is already a submerchant.");
        }
        
        // Verify submerchant exists
        Receiver submerchant = receiverRepository.findById(submerchantId)
                .orElseThrow(() -> new RuntimeException("Submerchant receiver not found with id: " + submerchantId));
        
        // Verify not linking to itself
        if (mainReceiverId.equals(submerchantId)) {
            throw new RuntimeException("Cannot link a receiver to itself as a submerchant.");
        }
        
        // Check if submerchant already has a parent
        if (submerchant.getParentReceiver() != null) {
            // If it has a different parent, update it to the new main merchant
            if (!submerchant.getParentReceiver().getId().equals(mainReceiverId)) {
                submerchant.setParentReceiver(mainReceiver);
            }
            // If it already has this parent, just reactivate it
        } else {
            // If no parent, link it to the main merchant
            submerchant.setParentReceiver(mainReceiver);
        }
        
        // Reactivate the submerchant (set status to ACTIVE)
        submerchant.setStatus(ReceiverStatus.ACTIVE);
        
        Receiver savedSubmerchant = receiverRepository.save(submerchant);
        
        return mapToResponse(savedSubmerchant);
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
        
        // Update MoMo account phone if provided
        if (request.getMomoAccountPhone() != null) {
            // Normalize phone number: remove non-digit characters
            String normalizedMomoPhone = request.getMomoAccountPhone().replaceAll("[^0-9]", "");
            submerchant.setMomoAccountPhone(normalizedMomoPhone);
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
        
        // Instead of deleting, just suspend to preserve records and information
        // Keep the parent relationship so we can still track it as a submerchant
        submerchant.setStatus(ReceiverStatus.SUSPENDED);
        receiverRepository.save(submerchant);
    }

    // Helper methods for dynamic queries to avoid parameter type issues with null values
    private BigDecimal sumSuccessfulAmountByReceiverAndFilters(UUID receiverId, LocalDateTime fromDate, 
                                                               LocalDateTime toDate, UUID categoryId) {
        StringBuilder sql = new StringBuilder(
            "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t " +
            "WHERE t.receiver_id = :receiverId " +
            "AND t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS'"
        );
        
        if (fromDate != null) {
            sql.append(" AND t.created_at >= :fromDate");
        }
        if (toDate != null) {
            sql.append(" AND t.created_at <= :toDate");
        }
        if (categoryId != null) {
            sql.append(" AND t.payment_category_id = :categoryId");
        }
        
        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("receiverId", receiverId);
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        if (categoryId != null) {
            query.setParameter("categoryId", categoryId);
        }
        
        Object result = query.getSingleResult();
        return result != null ? (BigDecimal) result : BigDecimal.ZERO;
    }

    private Long countAllTransactionsByReceiverAndFilters(UUID receiverId, LocalDateTime fromDate, 
                                                          LocalDateTime toDate, UUID categoryId) {
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FROM transactions t " +
            "WHERE t.receiver_id = :receiverId " +
            "AND t.transaction_type = 'PAYMENT'"
        );
        
        if (fromDate != null) {
            sql.append(" AND t.created_at >= :fromDate");
        }
        if (toDate != null) {
            sql.append(" AND t.created_at <= :toDate");
        }
        if (categoryId != null) {
            sql.append(" AND t.payment_category_id = :categoryId");
        }
        
        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("receiverId", receiverId);
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        if (categoryId != null) {
            query.setParameter("categoryId", categoryId);
        }
        
        Object result = query.getSingleResult();
        return result != null ? ((Number) result).longValue() : 0L;
    }

    private Long countSuccessfulTransactionsByReceiverAndFilters(UUID receiverId, LocalDateTime fromDate, 
                                                                 LocalDateTime toDate, UUID categoryId) {
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FROM transactions t " +
            "WHERE t.receiver_id = :receiverId " +
            "AND t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS'"
        );
        
        if (fromDate != null) {
            sql.append(" AND t.created_at >= :fromDate");
        }
        if (toDate != null) {
            sql.append(" AND t.created_at <= :toDate");
        }
        if (categoryId != null) {
            sql.append(" AND t.payment_category_id = :categoryId");
        }
        
        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("receiverId", receiverId);
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        if (categoryId != null) {
            query.setParameter("categoryId", categoryId);
        }
        
        Object result = query.getSingleResult();
        return result != null ? ((Number) result).longValue() : 0L;
    }

    private Long countDistinctUsersByReceiverAndDateRange(UUID receiverId, LocalDateTime fromDate, 
                                                          LocalDateTime toDate) {
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(DISTINCT t.user_id) FROM transactions t " +
            "WHERE t.receiver_id = :receiverId " +
            "AND t.transaction_type = 'PAYMENT'"
        );
        
        if (fromDate != null) {
            sql.append(" AND t.created_at >= :fromDate");
        }
        if (toDate != null) {
            sql.append(" AND t.created_at <= :toDate");
        }
        
        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("receiverId", receiverId);
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        
        Object result = query.getSingleResult();
        return result != null ? ((Number) result).longValue() : 0L;
    }

    private List<Object[]> getCategoryBreakdownByReceiver(UUID receiverId, LocalDateTime fromDate, 
                                                          LocalDateTime toDate) {
        StringBuilder sql = new StringBuilder(
            "SELECT pc.id, pc.name, COUNT(t.id), COALESCE(SUM(t.amount), 0) FROM transactions t " +
            "LEFT JOIN payment_categories pc ON t.payment_category_id = pc.id " +
            "WHERE t.receiver_id = :receiverId AND t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS' " +
            "AND t.payment_category_id IS NOT NULL"
        );
        
        if (fromDate != null) {
            sql.append(" AND t.created_at >= :fromDate");
        }
        if (toDate != null) {
            sql.append(" AND t.created_at <= :toDate");
        }
        
        sql.append(" GROUP BY pc.id, pc.name");
        
        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("receiverId", receiverId);
        if (fromDate != null) {
            query.setParameter("fromDate", fromDate);
        }
        if (toDate != null) {
            query.setParameter("toDate", toDate);
        }
        
        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        return results;
    }

    /**
     * Generates a unique transaction ID starting with "MOPAY"
     * Format: MOPAY + timestamp (milliseconds) + random alphanumeric string
     * Example: MOPAY1769123456789A3B2C1D
     */
    private String generateMopayTransactionId() {
        long timestamp = System.currentTimeMillis();
        // Generate a random alphanumeric string (6 characters)
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder randomPart = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 6; i++) {
            randomPart.append(chars.charAt(random.nextInt(chars.length())));
        }
        return "MOPAY" + timestamp + randomPart.toString();
    }
}

