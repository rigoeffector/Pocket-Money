package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.AssignNfcCardRequest;
import com.pocketmoney.pocketmoney.dto.CardDetailsResponse;
import com.pocketmoney.pocketmoney.dto.CreateUserRequest;
import com.pocketmoney.pocketmoney.dto.NfcCardResponse;
import com.pocketmoney.pocketmoney.dto.UpdateUserRequest;
import com.pocketmoney.pocketmoney.dto.MerchantBalanceInfo;
import com.pocketmoney.pocketmoney.dto.UserLoginResponse;
import com.pocketmoney.pocketmoney.dto.UserResponse;
import com.pocketmoney.pocketmoney.entity.MerchantUserBalance;
import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import com.pocketmoney.pocketmoney.entity.User;
import com.pocketmoney.pocketmoney.entity.UserStatus;
import com.pocketmoney.pocketmoney.repository.MerchantUserBalanceRepository;
import com.pocketmoney.pocketmoney.repository.ReceiverRepository;
import com.pocketmoney.pocketmoney.repository.UserRepository;
import com.pocketmoney.pocketmoney.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final MerchantUserBalanceRepository merchantUserBalanceRepository;
    private final ReceiverRepository receiverRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserService(UserRepository userRepository, MerchantUserBalanceRepository merchantUserBalanceRepository,
                      ReceiverRepository receiverRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.merchantUserBalanceRepository = merchantUserBalanceRepository;
        this.receiverRepository = receiverRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public UserResponse createUser(CreateUserRequest request) {
        // Normalize phone number: remove + and other non-digit characters, keep only digits
        String normalizedPhone = request.getPhoneNumber().replaceAll("[^0-9]", "");
        
        // Check if phone number already exists (using normalized phone)
        if (userRepository.existsByPhoneNumber(normalizedPhone)) {
            throw new RuntimeException("Phone number already exists");
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
            if (userRepository.existsByEmail(email)) {
                throw new RuntimeException("Email already exists");
            }
        }

        // Normalize NFC card ID: convert empty/blank strings to null
        String nfcCardId = (request.getNfcCardId() != null && !request.getNfcCardId().trim().isEmpty()) 
                ? request.getNfcCardId().trim() 
                : null;

        // Check if NFC card ID already exists (if provided)
        if (nfcCardId != null) {
            if (userRepository.existsByNfcCardId(nfcCardId)) {
                throw new RuntimeException("NFC card ID already assigned to another user. Each NFC card can only be assigned to one user.");
            }
        }

        // Create new user
        User user = new User();
        user.setFullNames(request.getFullNames());
        user.setPhoneNumber(normalizedPhone);
        user.setEmail(email);
        user.setPin(passwordEncoder.encode(request.getPin())); // Hash the PIN
        user.setAmountOnCard(request.getInitialAmount() != null ? request.getInitialAmount() : BigDecimal.ZERO);
        user.setAmountRemaining(request.getInitialAmount() != null ? request.getInitialAmount() : BigDecimal.ZERO);
        
        // Set NFC card if provided
        if (nfcCardId != null) {
            user.setIsAssignedNfcCard(true);
            user.setNfcCardId(nfcCardId);
        } else {
            user.setIsAssignedNfcCard(false);
        }

        User savedUser = userRepository.save(user);
        return mapToResponse(savedUser);
    }

    @Transactional(readOnly = true)
    public UserLoginResponse login(String phoneNumber, String pin) {
        // Normalize phone number: remove + and other non-digit characters, keep only digits
        String normalizedPhone = phoneNumber.replaceAll("[^0-9]", "");
        
        User user = userRepository.findByPhoneNumber(normalizedPhone)
                .orElseThrow(() -> new RuntimeException("Invalid phone number or PIN"));

        // Verify PIN
        if (!passwordEncoder.matches(pin, user.getPin())) {
            throw new RuntimeException("Invalid phone number or PIN");
        }

        // Check if user is active
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new RuntimeException("User account is not active. Status: " + user.getStatus());
        }

        // Generate JWT token
        String token = jwtUtil.generateUserToken(user.getPhoneNumber());

        // Map to login response
        UserLoginResponse response = new UserLoginResponse();
        response.setToken(token);
        response.setTokenType("Bearer");
        response.setUserType("USER");
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
    public UserResponse getUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));
        return mapToResponse(user);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public UserResponse getUserByPhoneNumber(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found with phone number: " + phoneNumber));
        return mapToResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponse getUserByNfcCardId(String nfcCardId) {
        User user = userRepository.findByNfcCardId(nfcCardId)
                .orElseThrow(() -> new RuntimeException("User not found with NFC card ID: " + nfcCardId));
        return mapToResponse(user);
    }

    @Transactional(readOnly = true)
    public CardDetailsResponse getCardDetailsByNfcCardId(String nfcCardId) {
        User user = userRepository.findByNfcCardId(nfcCardId)
                .orElseThrow(() -> new RuntimeException("Card not found with NFC card ID: " + nfcCardId));
        
        CardDetailsResponse response = new CardDetailsResponse();
        response.setNfcCardId(user.getNfcCardId());
        response.setUserId(user.getId());
        response.setFullNames(user.getFullNames());
        response.setPhoneNumber(user.getPhoneNumber());
        response.setEmail(user.getEmail());
        response.setIsAssignedNfcCard(user.getIsAssignedNfcCard());
        response.setAmountOnCard(user.getAmountOnCard());
        response.setAmountRemaining(user.getAmountRemaining());
        response.setStatus(user.getStatus());
        response.setLastTransactionDate(user.getLastTransactionDate());
        response.setCardAssignedAt(user.getUpdatedAt()); // Using updatedAt as approximation for card assignment
        return response;
    }

    public UserResponse updateUser(UUID id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));

        // Update fields if provided
        if (request.getFullNames() != null) {
            user.setFullNames(request.getFullNames());
        }

        if (request.getPhoneNumber() != null) {
            // Normalize phone number: remove + and other non-digit characters, keep only digits
            String normalizedPhone = request.getPhoneNumber().replaceAll("[^0-9]", "");
            
            if (!normalizedPhone.equals(user.getPhoneNumber())) {
                // Restrict phone number change if user has an NFC card assigned
                if (user.getIsAssignedNfcCard() != null && user.getIsAssignedNfcCard() 
                        && user.getNfcCardId() != null && !user.getNfcCardId().isEmpty()) {
                    throw new RuntimeException("Cannot change phone number. User has an NFC card assigned. Phone number must remain the same for the same card.");
                }
                
                // Check if phone number already exists (one phone number per user)
                if (userRepository.existsByPhoneNumber(normalizedPhone)) {
                    throw new RuntimeException("Phone number already exists. Each phone number can only belong to one user.");
                }
                user.setPhoneNumber(normalizedPhone);
            }
        }

        if (request.getEmail() != null) {
            if (!request.getEmail().isEmpty() && !request.getEmail().equals(user.getEmail())) {
                if (userRepository.existsByEmail(request.getEmail())) {
                    throw new RuntimeException("Email already exists");
                }
            }
            user.setEmail(request.getEmail());
        }

        if (request.getIsAssignedNfcCard() != null) {
            user.setIsAssignedNfcCard(request.getIsAssignedNfcCard());
        }

        if (request.getNfcCardId() != null) {
            if (!request.getNfcCardId().isEmpty() && !request.getNfcCardId().equals(user.getNfcCardId())) {
                if (userRepository.existsByNfcCardId(request.getNfcCardId())) {
                    throw new RuntimeException("NFC card ID already assigned to another user. Each NFC card can only be assigned to one user.");
                }
            }
            user.setNfcCardId(request.getNfcCardId());
            if (request.getNfcCardId().isEmpty()) {
                user.setIsAssignedNfcCard(false);
            }
        }

        if (request.getAmountOnCard() != null) {
            user.setAmountOnCard(request.getAmountOnCard());
        }

        if (request.getAmountRemaining() != null) {
            user.setAmountRemaining(request.getAmountRemaining());
        }

        if (request.getStatus() != null) {
            try {
                user.setStatus(UserStatus.valueOf(request.getStatus().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Invalid status: " + request.getStatus());
            }
        }

        User updatedUser = userRepository.save(user);
        return mapToResponse(updatedUser);
    }

    public void deleteUser(UUID id) {
        if (!userRepository.existsById(id)) {
            throw new RuntimeException("User not found with id: " + id);
        }
        userRepository.deleteById(id);
    }

    public void createPin(UUID userId, String pin) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

        // Check if PIN already exists (non-null and not empty)
        if (user.getPin() != null && !user.getPin().isEmpty()) {
            throw new RuntimeException("PIN already exists. Use update PIN endpoint to change it.");
        }

        user.setPin(passwordEncoder.encode(pin));
        userRepository.save(user);
    }

    public void updatePin(UUID userId, String currentPin, String newPin) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

        // Verify current PIN
        if (!passwordEncoder.matches(currentPin, user.getPin())) {
            throw new RuntimeException("Current PIN is incorrect");
        }

        // Update to new PIN
        user.setPin(passwordEncoder.encode(newPin));
        userRepository.save(user);
    }

    public void resetPin(UUID userId, String newPin) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

        // Reset PIN without verification (admin action)
        user.setPin(passwordEncoder.encode(newPin));
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public NfcCardResponse getMyNfcCard(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

        NfcCardResponse response = new NfcCardResponse();
        response.setUserId(user.getId());
        response.setFullNames(user.getFullNames());
        response.setPhoneNumber(user.getPhoneNumber());
        response.setIsAssignedNfcCard(user.getIsAssignedNfcCard());
        response.setNfcCardId(user.getNfcCardId());
        return response;
    }

    public NfcCardResponse assignNfcCard(UUID userId, AssignNfcCardRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

        // Check if NFC card ID already exists (assigned to another user)
        if (userRepository.existsByNfcCardId(request.getNfcCardId())) {
            // Check if it's already assigned to this user
            if (user.getNfcCardId() == null || !user.getNfcCardId().equals(request.getNfcCardId())) {
                throw new RuntimeException("NFC card ID already assigned to another user. Each NFC card can only be assigned to one user.");
            }
        }

        // Assign NFC card
        user.setNfcCardId(request.getNfcCardId());
        user.setIsAssignedNfcCard(true);

        // Create/Update PIN
        user.setPin(passwordEncoder.encode(request.getPin()));

        User savedUser = userRepository.save(user);

        NfcCardResponse response = new NfcCardResponse();
        response.setUserId(savedUser.getId());
        response.setFullNames(savedUser.getFullNames());
        response.setPhoneNumber(savedUser.getPhoneNumber());
        response.setIsAssignedNfcCard(savedUser.getIsAssignedNfcCard());
        response.setNfcCardId(savedUser.getNfcCardId());
        return response;
    }

    /**
     * Assign NFC card to user by phone number (for flexible merchants only)
     * This endpoint links the phone number with the card and creates/updates merchant balance
     */
    public NfcCardResponse assignNfcCardByPhoneNumber(String phoneNumber, AssignNfcCardRequest request) {
        // Get current authenticated receiver from security context
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        
        String currentUsername = authentication.getName();
        Receiver merchant = receiverRepository.findByUsername(currentUsername)
                .orElseThrow(() -> new RuntimeException("Merchant not found with username: " + currentUsername));
        
        // Verify merchant is a main merchant (not a submerchant)
        if (merchant.getParentReceiver() != null) {
            throw new RuntimeException("Only main merchants can assign NFC cards. Your merchant account is a submerchant.");
        }
        
        // Verify merchant is flexible (only flexible merchants can use this endpoint)
        boolean isFlexible = merchant.getIsFlexible() != null && merchant.getIsFlexible();
        if (!isFlexible) {
            throw new RuntimeException(String.format(
                "Only flexible merchants can assign NFC cards by phone number. Please contact admin to enable flexible mode for your merchant account. Merchant ID: %s, Merchant Name: %s", 
                merchant.getId(), merchant.getCompanyName()));
        }
        
        // Verify merchant is active
        if (merchant.getStatus() != ReceiverStatus.ACTIVE) {
            throw new RuntimeException("Merchant account is not active. Status: " + merchant.getStatus());
        }
        
        // Normalize phone number
        String normalizedPhone = normalizePhoneTo12Digits(phoneNumber);
        
        // Find user by phone number - try multiple formats
        User user = userRepository.findByPhoneNumber(normalizedPhone).orElse(null);
        
        // If not found with 12-digit format, try with just digits
        if (user == null) {
            String phoneDigitsOnly = phoneNumber.replaceAll("[^0-9]", "");
            user = userRepository.findByPhoneNumber(phoneDigitsOnly).orElse(null);
        }
        
        // If still not found, try with 0-prefixed format
        if (user == null) {
            String phoneDigitsOnly = phoneNumber.replaceAll("[^0-9]", "");
            if (!phoneDigitsOnly.startsWith("0") && phoneDigitsOnly.length() == 9) {
                user = userRepository.findByPhoneNumber("0" + phoneDigitsOnly).orElse(null);
            }
        }
        
        // If still not found, try extracting last 9 digits and adding 0 prefix
        if (user == null) {
            String phoneDigitsOnly = phoneNumber.replaceAll("[^0-9]", "");
            if (phoneDigitsOnly.length() >= 9) {
                String last9 = phoneDigitsOnly.substring(phoneDigitsOnly.length() - 9);
                user = userRepository.findByPhoneNumber("0" + last9).orElse(null);
            }
        }
        
        // If user doesn't exist, create a new user with minimal information
        if (user == null) {
            logger.info("User not found with phone number: {}. Creating new user automatically.", phoneNumber);
            String phoneDigitsOnly = phoneNumber.replaceAll("[^0-9]", "");
            User newUser = new User();
            newUser.setFullNames("User " + phoneDigitsOnly);
            newUser.setPhoneNumber(normalizedPhone);
            newUser.setPin(passwordEncoder.encode("0000")); // Default PIN, will be updated below
            newUser.setIsAssignedNfcCard(false);
            newUser.setAmountOnCard(BigDecimal.ZERO);
            newUser.setAmountRemaining(BigDecimal.ZERO);
            newUser.setStatus(UserStatus.ACTIVE);
            user = userRepository.save(newUser);
            logger.info("Created new user with ID: {}, Phone: {}", user.getId(), normalizedPhone);
        }
        
        // Check if user is active
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new RuntimeException("User account is not active. Status: " + user.getStatus());
        }
        
        // Check if the NFC card ID is already assigned to a different user (different phone number)
        if (userRepository.existsByNfcCardId(request.getNfcCardId())) {
            User existingUser = userRepository.findByNfcCardId(request.getNfcCardId())
                .orElse(null);
            
            if (existingUser != null) {
                // Check if it's already assigned to this user (same user, just updating PIN)
                if (existingUser.getId().equals(user.getId())) {
                    // Same user, same card - just updating PIN, this is allowed
                    logger.info("User {} already has NFC card {} assigned. Updating PIN only.", 
                        user.getPhoneNumber(), request.getNfcCardId());
                } else {
                    // Card is assigned to a different user - throw error
                    throw new RuntimeException(String.format(
                        "NFC card ID '%s' is already assigned to another user with phone number '%s'. " +
                        "Each NFC card can only be assigned to one phone number. " +
                        "Please use a different card or contact the user with phone number '%s' to unassign the card first.",
                        request.getNfcCardId(), existingUser.getPhoneNumber(), existingUser.getPhoneNumber()));
                }
            }
        }
        
        // Handle NFC card replacement logic
        // If user already has a different NFC card assigned, unassign it first (only if new card is not assigned to someone else)
        if (user.getNfcCardId() != null && !user.getNfcCardId().equals(request.getNfcCardId())) {
            logger.info("User {} already has NFC card {} assigned. Replacing with new card {}.", 
                user.getPhoneNumber(), user.getNfcCardId(), request.getNfcCardId());
            // The old card will be automatically unassigned when we set the new one
        }
        
        // Assign NFC card (or replace existing one)
        user.setNfcCardId(request.getNfcCardId());
        user.setIsAssignedNfcCard(true);
        
        // Create/Update PIN
        user.setPin(passwordEncoder.encode(request.getPin()));
        
        User savedUser = userRepository.save(user);
        
        // Create/Update MerchantUserBalance to link the balance
        MerchantUserBalance merchantBalance = merchantUserBalanceRepository
                .findByUserIdAndReceiverId(user.getId(), merchant.getId())
                .orElse(null);
        
        if (merchantBalance == null) {
            // Create new merchant balance record
            merchantBalance = new MerchantUserBalance();
            merchantBalance.setUser(user);
            merchantBalance.setReceiver(merchant);
            merchantBalance.setBalance(BigDecimal.ZERO);
            merchantBalance.setTotalToppedUp(BigDecimal.ZERO);
            merchantUserBalanceRepository.save(merchantBalance);
        }
        // If merchant balance already exists, we keep it as is (don't reset it)
        
        NfcCardResponse response = new NfcCardResponse();
        response.setUserId(savedUser.getId());
        response.setFullNames(savedUser.getFullNames());
        response.setPhoneNumber(savedUser.getPhoneNumber());
        response.setIsAssignedNfcCard(savedUser.getIsAssignedNfcCard());
        response.setNfcCardId(savedUser.getNfcCardId());
        return response;
    }
    
    /**
     * Normalize phone number to 12 digits (250XXXXXXXXX format)
     */
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
                return "250" + without250.substring(without250.length() - 9);
            }
        }
        
        // If 9 digits and starts with 0, remove 0 and add 250
        if (cleaned.length() == 9 && cleaned.startsWith("0")) {
            return "250" + cleaned.substring(1);
        }
        
        // If 9 digits and doesn't start with 0, add 250
        if (cleaned.length() == 9) {
            return "250" + cleaned;
        }
        
        // If 10 digits and starts with 0, remove 0 and add 250
        if (cleaned.length() == 10 && cleaned.startsWith("0")) {
            return "250" + cleaned.substring(1);
        }
        
        // If 10 digits and doesn't start with 0, take last 9 and add 250
        if (cleaned.length() == 10) {
            return "250" + cleaned.substring(1);
        }
        
        // If 12 digits but doesn't start with 250, take last 9 and add 250
        if (cleaned.length() >= 12) {
            return "250" + cleaned.substring(cleaned.length() - 9);
        }
        
        throw new RuntimeException("Invalid phone number format: " + phone);
    }

    private UserResponse mapToResponse(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setFullNames(user.getFullNames());
        response.setPhoneNumber(user.getPhoneNumber());
        response.setEmail(user.getEmail());
        response.setIsAssignedNfcCard(user.getIsAssignedNfcCard());
        response.setNfcCardId(user.getNfcCardId());
        response.setAmountOnCard(user.getAmountOnCard());
        response.setAmountRemaining(user.getAmountRemaining());
        
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
                .collect(Collectors.toList());
        response.setMerchantBalances(merchantBalanceInfos);
        
        response.setStatus(user.getStatus());
        response.setLastTransactionDate(user.getLastTransactionDate());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }
}

