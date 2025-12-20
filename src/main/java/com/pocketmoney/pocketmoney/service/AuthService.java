package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.AuthResponse;
import com.pocketmoney.pocketmoney.dto.LoginRequest;
import com.pocketmoney.pocketmoney.dto.RegisterRequest;
import com.pocketmoney.pocketmoney.entity.Auth;
import com.pocketmoney.pocketmoney.entity.Role;
import com.pocketmoney.pocketmoney.repository.AuthRepository;
import com.pocketmoney.pocketmoney.util.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {

    private final AuthRepository authRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(AuthRepository authRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.authRepository = authRepository;
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

    public AuthResponse login(LoginRequest request) {
        // Find user by username
        Auth auth = authRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("Invalid username or password"));

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
}

