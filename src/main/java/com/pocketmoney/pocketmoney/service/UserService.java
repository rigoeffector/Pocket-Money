package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.AssignNfcCardRequest;
import com.pocketmoney.pocketmoney.dto.CardDetailsResponse;
import com.pocketmoney.pocketmoney.dto.CreateUserRequest;
import com.pocketmoney.pocketmoney.dto.NfcCardResponse;
import com.pocketmoney.pocketmoney.dto.UpdateUserRequest;
import com.pocketmoney.pocketmoney.dto.UserLoginResponse;
import com.pocketmoney.pocketmoney.dto.UserResponse;
import com.pocketmoney.pocketmoney.entity.User;
import com.pocketmoney.pocketmoney.entity.UserStatus;
import com.pocketmoney.pocketmoney.repository.UserRepository;
import com.pocketmoney.pocketmoney.util.JwtUtil;
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

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public UserResponse createUser(CreateUserRequest request) {
        // Check if phone number already exists
        if (userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new RuntimeException("Phone number already exists");
        }

        // Check if email already exists (if provided)
        if (request.getEmail() != null && !request.getEmail().isEmpty()) {
            if (userRepository.existsByEmail(request.getEmail())) {
                throw new RuntimeException("Email already exists");
            }
        }

        // Check if NFC card ID already exists (if provided)
        if (request.getNfcCardId() != null && !request.getNfcCardId().isEmpty()) {
            if (userRepository.existsByNfcCardId(request.getNfcCardId())) {
                throw new RuntimeException("NFC card ID already assigned to another user. Each NFC card can only be assigned to one user.");
            }
        }

        // Create new user
        User user = new User();
        user.setFullNames(request.getFullNames());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setEmail(request.getEmail());
        user.setPin(passwordEncoder.encode(request.getPin())); // Hash the PIN
        user.setAmountOnCard(request.getInitialAmount() != null ? request.getInitialAmount() : BigDecimal.ZERO);
        user.setAmountRemaining(request.getInitialAmount() != null ? request.getInitialAmount() : BigDecimal.ZERO);
        
        // Set NFC card if provided
        if (request.getNfcCardId() != null && !request.getNfcCardId().isEmpty()) {
            user.setIsAssignedNfcCard(true);
            user.setNfcCardId(request.getNfcCardId());
        } else {
            user.setIsAssignedNfcCard(false);
        }

        User savedUser = userRepository.save(user);
        return mapToResponse(savedUser);
    }

    @Transactional(readOnly = true)
    public UserLoginResponse login(String phoneNumber, String pin) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
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

        if (request.getPhoneNumber() != null && !request.getPhoneNumber().equals(user.getPhoneNumber())) {
            // Restrict phone number change if user has an NFC card assigned
            if (user.getIsAssignedNfcCard() != null && user.getIsAssignedNfcCard() 
                    && user.getNfcCardId() != null && !user.getNfcCardId().isEmpty()) {
                throw new RuntimeException("Cannot change phone number. User has an NFC card assigned. Phone number must remain the same for the same card.");
            }
            
            // Check if phone number already exists (one phone number per user)
            if (userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
                throw new RuntimeException("Phone number already exists. Each phone number can only belong to one user.");
            }
            user.setPhoneNumber(request.getPhoneNumber());
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
        response.setStatus(user.getStatus());
        response.setLastTransactionDate(user.getLastTransactionDate());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }
}

