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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

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
    public AuthResponse switchMerchant(UUID receiverId) {
        // Verify receiver exists and is active
        Receiver receiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found"));
        
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

        return response;
    }
}

