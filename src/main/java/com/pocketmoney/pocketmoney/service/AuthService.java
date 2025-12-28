package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.AuthResponse;
import com.pocketmoney.pocketmoney.dto.LoginRequest;
import com.pocketmoney.pocketmoney.dto.RegisterRequest;
import com.pocketmoney.pocketmoney.entity.Auth;
import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import com.pocketmoney.pocketmoney.entity.Role;
import com.pocketmoney.pocketmoney.repository.AuthRepository;
import com.pocketmoney.pocketmoney.repository.ReceiverRepository;
import com.pocketmoney.pocketmoney.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final AuthRepository authRepository;
    private final ReceiverRepository receiverRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(AuthRepository authRepository, ReceiverRepository receiverRepository, 
                      PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.authRepository = authRepository;
        this.receiverRepository = receiverRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public AuthResponse register(RegisterRequest request) {
        // Check if username already exists
        if (authRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already exists");
        }

        // Check if email already exists
        if (authRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already exists");
        }

        // Create new auth entity
        Auth auth = new Auth();
        auth.setUsername(request.getUsername());
        auth.setEmail(request.getEmail());
        auth.setPassword(passwordEncoder.encode(request.getPassword())); // Hash the password
        auth.setRole(request.getRole() != null ? request.getRole() : Role.USER);

        // Save to database
        Auth savedAuth = authRepository.save(auth);

        // Generate JWT token
        String token = jwtUtil.generateToken(savedAuth.getUsername(), savedAuth.getRole());

        // Create response with user information
        AuthResponse response = new AuthResponse();
        response.setToken(token);
        response.setTokenType("Bearer");
        response.setId(savedAuth.getId());
        response.setUsername(savedAuth.getUsername());
        response.setEmail(savedAuth.getEmail());
        response.setRole(savedAuth.getRole());
        response.setCreatedAt(savedAuth.getCreatedAt());
        response.setUpdatedAt(savedAuth.getUpdatedAt());

        return response;
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // Try to find in Auth table first (for ADMIN/USER)
        Optional<Auth> authOptional = authRepository.findByUsername(request.getUsername());
        
        if (authOptional.isPresent()) {
            Auth auth = authOptional.get();
            
            // Verify password
            if (!passwordEncoder.matches(request.getPassword(), auth.getPassword())) {
                throw new RuntimeException("Invalid username or password");
            }

            // Generate JWT token
            String token = jwtUtil.generateToken(auth.getUsername(), auth.getRole());

            // Create response with user information
            AuthResponse response = new AuthResponse();
            response.setToken(token);
            response.setTokenType("Bearer");
            response.setId(auth.getId());
            response.setUsername(auth.getUsername());
            response.setEmail(auth.getEmail());
            response.setRole(auth.getRole());
            response.setCreatedAt(auth.getCreatedAt());
            response.setUpdatedAt(auth.getUpdatedAt());

            return response;
        }
        
        // If not found in Auth table, try Receiver table (for RECEIVER/MERCHANT)
        Optional<Receiver> receiverOptional = receiverRepository.findByUsername(request.getUsername());
        
        if (receiverOptional.isPresent()) {
            Receiver receiver = receiverOptional.get();
            
            // Verify password
            if (!passwordEncoder.matches(request.getPassword(), receiver.getPassword())) {
                throw new RuntimeException("Invalid username or password");
            }
            
            // Check if receiver is active
            if (receiver.getStatus() != ReceiverStatus.ACTIVE) {
                throw new RuntimeException("Receiver account is not active. Status: " + receiver.getStatus());
            }

            // Generate JWT token with RECEIVER role
            String token = jwtUtil.generateReceiverToken(receiver.getUsername());

            // Get available merchants/submerchants list
            List<AuthResponse.MerchantInfo> availableMerchants = getAvailableMerchants(receiver);

            // Determine balance owner (for shared balance if submerchant)
            Receiver balanceOwner = receiver.getParentReceiver() != null ? receiver.getParentReceiver() : receiver;
            
            // Create response with all receiver information
            AuthResponse response = new AuthResponse();
            response.setToken(token);
            response.setTokenType("Bearer");
            response.setId(receiver.getId());
            response.setUsername(receiver.getUsername());
            response.setEmail(receiver.getEmail());
            response.setRole(Role.RECEIVER);
            response.setCreatedAt(receiver.getCreatedAt());
            response.setUpdatedAt(receiver.getUpdatedAt());
            
            // All receiver details
            response.setCompanyName(receiver.getCompanyName());
            response.setManagerName(receiver.getManagerName());
            response.setReceiverPhone(receiver.getReceiverPhone());
            response.setAccountNumber(receiver.getAccountNumber());
            response.setStatus(receiver.getStatus());
            response.setAddress(receiver.getAddress());
            response.setDescription(receiver.getDescription());
            
            // Wallet and balance information (use shared balance if submerchant)
            response.setWalletBalance(balanceOwner.getWalletBalance());
            response.setTotalReceived(balanceOwner.getTotalReceived());
            response.setAssignedBalance(balanceOwner.getAssignedBalance());
            response.setRemainingBalance(balanceOwner.getRemainingBalance());
            response.setDiscountPercentage(balanceOwner.getDiscountPercentage());
            response.setUserBonusPercentage(balanceOwner.getUserBonusPercentage());
            response.setLastTransactionDate(receiver.getLastTransactionDate());
            
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
                long submerchantCount = receiverRepository.countByParentReceiverId(receiver.getId());
                response.setSubmerchantCount((int) submerchantCount);
            }
            
            response.setAvailableMerchants(availableMerchants);
            // Set isSwitchingClaiming to false on normal login (not switching)
            response.setIsSwitchingClaiming(false);
            // Set isDoneByMain to false on normal login (not switching)
            response.setIsDoneByMain(false);

            return response;
        }
        
        // If not found in either table
        throw new RuntimeException("Invalid username or password");
    }

    public void resetPassword(UUID authId, String newPassword) {
        Auth auth = authRepository.findById(authId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + authId));

        // Reset password (admin action - no current password verification needed)
        auth.setPassword(passwordEncoder.encode(newPassword));
        authRepository.save(auth);
    }

    public void resetPasswordByUsername(String username, String newPassword) {
        Auth auth = authRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found with username: " + username));

        // Reset password (admin action - no current password verification needed)
        auth.setPassword(passwordEncoder.encode(newPassword));
        authRepository.save(auth);
    }

    @Transactional(readOnly = true)
    private List<AuthResponse.MerchantInfo> getAvailableMerchants(Receiver receiver) {
        List<AuthResponse.MerchantInfo> merchants = new ArrayList<>();
        
        // Determine the main merchant
        Receiver mainMerchant = receiver.getParentReceiver() != null ? receiver.getParentReceiver() : receiver;
        
        // Add main merchant to list (always first)
        AuthResponse.MerchantInfo mainInfo = new AuthResponse.MerchantInfo();
        mainInfo.setId(mainMerchant.getId());
        mainInfo.setUsername(mainMerchant.getUsername());
        mainInfo.setCompanyName(mainMerchant.getCompanyName());
        mainInfo.setManagerName(mainMerchant.getManagerName());
        mainInfo.setReceiverPhone(mainMerchant.getReceiverPhone());
        mainInfo.setEmail(mainMerchant.getEmail());
        mainInfo.setIsMainMerchant(true);
        mainInfo.setParentReceiverId(null);
        mainInfo.setParentReceiverCompanyName(null);
        merchants.add(mainInfo);
        
        // Add all submerchants (including the current receiver if it's a submerchant)
        List<Receiver> submerchants = receiverRepository.findByParentReceiverId(mainMerchant.getId());
        for (Receiver submerchant : submerchants) {
            AuthResponse.MerchantInfo subInfo = new AuthResponse.MerchantInfo();
            subInfo.setId(submerchant.getId());
            subInfo.setUsername(submerchant.getUsername());
            subInfo.setCompanyName(submerchant.getCompanyName());
            subInfo.setManagerName(submerchant.getManagerName());
            subInfo.setReceiverPhone(submerchant.getReceiverPhone());
            subInfo.setEmail(submerchant.getEmail());
            subInfo.setIsMainMerchant(false);
            subInfo.setParentReceiverId(mainMerchant.getId());
            subInfo.setParentReceiverCompanyName(mainMerchant.getCompanyName());
            merchants.add(subInfo);
        }
        
        return merchants;
    }

    @Transactional(readOnly = true)
    public AuthResponse switchMerchant(UUID receiverId, String currentToken) {
        // Get current authenticated user from SecurityContext
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        
        String currentUsername = authentication.getName();
        
        // Verify current user is a RECEIVER (main merchant)
        Optional<Receiver> currentReceiverOptional = receiverRepository.findByUsername(currentUsername);
        if (currentReceiverOptional.isEmpty()) {
            throw new RuntimeException("Only main merchants can switch to view submerchants");
        }
        
        Receiver currentReceiver = currentReceiverOptional.get();
        
        // Verify current receiver is a main merchant (has no parent)
        if (currentReceiver.getParentReceiver() != null) {
            throw new RuntimeException("Only main merchants can switch to view submerchants");
        }
        
        if (currentReceiver.getStatus() != ReceiverStatus.ACTIVE) {
            throw new RuntimeException("Main merchant account is not active. Status: " + currentReceiver.getStatus());
        }
        
        // Verify target receiver exists and is active
        Receiver targetReceiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found"));
        
        if (targetReceiver.getStatus() != ReceiverStatus.ACTIVE) {
            throw new RuntimeException("Receiver account is not active. Status: " + targetReceiver.getStatus());
        }
        
        // Verify target receiver is a submerchant of the current main merchant
        if (targetReceiver.getParentReceiver() == null || !targetReceiver.getParentReceiver().getId().equals(currentReceiver.getId())) {
            throw new RuntimeException("Cannot switch to this receiver. It is not a submerchant of your main merchant account.");
        }

        // Generate new RECEIVER token with main merchant's username and viewAsReceiverId claim
        // The token will ALWAYS have the main merchant's username as subject, not the submerchant's
        // This ensures the token remains the main merchant's token, just with viewAsReceiverId for context
        logger.info("Switching merchant: Main merchant username={}, ID={}, Submerchant ID={}", 
                currentReceiver.getUsername(), currentReceiver.getId(), receiverId);
        String token = jwtUtil.generateReceiverTokenWithViewAs(currentReceiver.getUsername(), receiverId);
        logger.info("Generated token with main merchant username: {}", currentReceiver.getUsername());

        // Get available merchants/submerchants list (main merchant + all its submerchants)
        List<AuthResponse.MerchantInfo> availableMerchants = getAvailableMerchants(currentReceiver);

        // Determine balance owner (for shared balance if submerchant - should be main merchant)
        Receiver balanceOwner = currentReceiver;
        
        // Create response with main merchant information but submerchant viewing context
        AuthResponse response = new AuthResponse();
        response.setToken(token);
        response.setTokenType("Bearer");
        response.setId(currentReceiver.getId()); // Main merchant ID
        response.setUsername(currentReceiver.getUsername()); // Main merchant username
        response.setEmail(currentReceiver.getEmail()); // Main merchant email
        response.setRole(Role.RECEIVER); // Keep RECEIVER role
        response.setCreatedAt(currentReceiver.getCreatedAt());
        response.setUpdatedAt(currentReceiver.getUpdatedAt());
        
        // Add submerchant information (the one being viewed)
        // IMPORTANT: viewAsReceiverId must be in the response so frontend knows which submerchant is being viewed
        response.setViewAsReceiverId(receiverId);
        // Set isSwitchingClaiming to true to indicate user is switching/viewing as another merchant
        response.setIsSwitchingClaiming(true);
        // Set isDoneByMain to true to indicate the switch was done by the main merchant
        response.setIsDoneByMain(true);
        response.setCompanyName(targetReceiver.getCompanyName());
        response.setManagerName(targetReceiver.getManagerName());
        response.setReceiverPhone(targetReceiver.getReceiverPhone());
        response.setAccountNumber(targetReceiver.getAccountNumber());
        response.setStatus(targetReceiver.getStatus());
        response.setAddress(targetReceiver.getAddress());
        response.setDescription(targetReceiver.getDescription());
        
        // Wallet and balance information (use main merchant's shared balance)
        response.setWalletBalance(balanceOwner.getWalletBalance());
        response.setTotalReceived(balanceOwner.getTotalReceived());
        response.setAssignedBalance(balanceOwner.getAssignedBalance());
        response.setRemainingBalance(balanceOwner.getRemainingBalance());
        response.setDiscountPercentage(balanceOwner.getDiscountPercentage());
        response.setUserBonusPercentage(balanceOwner.getUserBonusPercentage());
        response.setLastTransactionDate(targetReceiver.getLastTransactionDate());
        
        // Submerchant relationship info (target receiver is a submerchant of main merchant)
        // IMPORTANT: isMainMerchant is ALWAYS true when switching because the user IS the main merchant, just viewing submerchant data
        response.setParentReceiverId(currentReceiver.getId());
        response.setParentReceiverCompanyName(currentReceiver.getCompanyName());
        response.setIsMainMerchant(true); // ALWAYS true when switching - user is main merchant, just viewing submerchant context
        // Count submerchants for the main merchant
        long submerchantCount = receiverRepository.countByParentReceiverId(currentReceiver.getId());
        response.setSubmerchantCount((int) submerchantCount);
        
        response.setAvailableMerchants(availableMerchants);

        return response;
    }

    @Transactional(readOnly = true)
    public AuthResponse switchBackToMainMerchant(String currentToken) {
        // Get current authenticated user from SecurityContext
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        
        String currentUsername = authentication.getName();
        
        // Verify current user is a RECEIVER (main merchant)
        Optional<Receiver> currentReceiverOptional = receiverRepository.findByUsername(currentUsername);
        if (currentReceiverOptional.isEmpty()) {
            throw new RuntimeException("Only main merchants can switch back");
        }
        
        Receiver currentReceiver = currentReceiverOptional.get();
        
        // Verify current receiver is a main merchant (has no parent)
        if (currentReceiver.getParentReceiver() != null) {
            throw new RuntimeException("Only main merchants can switch back");
        }
        
        if (currentReceiver.getStatus() != ReceiverStatus.ACTIVE) {
            throw new RuntimeException("Main merchant account is not active. Status: " + currentReceiver.getStatus());
        }
        
        // Generate new RECEIVER token without viewAsReceiverId claim (back to normal main merchant view)
        String token = jwtUtil.generateReceiverToken(currentReceiver.getUsername());
        
        // Get available merchants/submerchants list
        List<AuthResponse.MerchantInfo> availableMerchants = getAvailableMerchants(currentReceiver);
        
        // Determine balance owner (main merchant)
        Receiver balanceOwner = currentReceiver;
        
        // Create response with main merchant information
        AuthResponse response = new AuthResponse();
        response.setToken(token);
        response.setTokenType("Bearer");
        response.setId(currentReceiver.getId());
        response.setUsername(currentReceiver.getUsername());
        response.setEmail(currentReceiver.getEmail());
        response.setRole(Role.RECEIVER);
        response.setCreatedAt(currentReceiver.getCreatedAt());
        response.setUpdatedAt(currentReceiver.getUpdatedAt());
        
        // Main merchant details
        response.setCompanyName(currentReceiver.getCompanyName());
        response.setManagerName(currentReceiver.getManagerName());
        response.setReceiverPhone(currentReceiver.getReceiverPhone());
        response.setAccountNumber(currentReceiver.getAccountNumber());
        response.setStatus(currentReceiver.getStatus());
        response.setAddress(currentReceiver.getAddress());
        response.setDescription(currentReceiver.getDescription());
        
        // Wallet and balance information
        response.setWalletBalance(balanceOwner.getWalletBalance());
        response.setTotalReceived(balanceOwner.getTotalReceived());
        response.setAssignedBalance(balanceOwner.getAssignedBalance());
        response.setRemainingBalance(balanceOwner.getRemainingBalance());
        response.setDiscountPercentage(balanceOwner.getDiscountPercentage());
        response.setUserBonusPercentage(balanceOwner.getUserBonusPercentage());
        response.setLastTransactionDate(currentReceiver.getLastTransactionDate());
        
        // Main merchant info (no parent)
        response.setParentReceiverId(null);
        response.setParentReceiverCompanyName(null);
        response.setIsMainMerchant(true);
        long submerchantCount = receiverRepository.countByParentReceiverId(currentReceiver.getId());
        response.setSubmerchantCount((int) submerchantCount);
        
        // No merchant viewing context (viewAsReceiverId is null)
        response.setViewAsReceiverId(null);
        // Set isSwitchingClaiming to false when switching back to main merchant view
        response.setIsSwitchingClaiming(false);
        // Set isDoneByMain to false when switching back (no longer viewing as submerchant)
        response.setIsDoneByMain(false);
        response.setAvailableMerchants(availableMerchants);

        return response;
    }
}

