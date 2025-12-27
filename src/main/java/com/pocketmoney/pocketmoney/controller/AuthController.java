package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.AuthResponse;
import com.pocketmoney.pocketmoney.dto.LoginRequest;
import com.pocketmoney.pocketmoney.dto.RegisterRequest;
import com.pocketmoney.pocketmoney.dto.ResetPasswordRequest;
import com.pocketmoney.pocketmoney.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        try {
            AuthResponse response = authService.register(request);
            return ResponseEntity.ok(ApiResponse.success("User registered successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        try {
            AuthResponse response = authService.login(request);
            return ResponseEntity.ok(ApiResponse.success("Login successful", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody ResetPasswordRequest request) {
        try {
            authService.resetPassword(id, request.getNewPassword());
            return ResponseEntity.ok(ApiResponse.success("Password reset successfully", null));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/reset-password/{username}")
    public ResponseEntity<ApiResponse<Void>> resetPasswordByUsername(
            @PathVariable String username,
            @Valid @RequestBody ResetPasswordRequest request) {
        try {
            authService.resetPasswordByUsername(username, request.getNewPassword());
            return ResponseEntity.ok(ApiResponse.success("Password reset successfully", null));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/admin/reset-password/{username}")
    public ResponseEntity<ApiResponse<Void>> resetAdminPasswordByUsername(
            @PathVariable String username,
            @Valid @RequestBody ResetPasswordRequest request) {
        try {
            // This endpoint is specifically for admin password reset
            authService.resetPasswordByUsername(username, request.getNewPassword());
            return ResponseEntity.ok(ApiResponse.success("Admin password reset successfully", null));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/admin/{id}/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetAdminPasswordById(
            @PathVariable UUID id,
            @Valid @RequestBody ResetPasswordRequest request) {
        try {
            // This endpoint is specifically for admin password reset by ID
            authService.resetPassword(id, request.getNewPassword());
            return ResponseEntity.ok(ApiResponse.success("Admin password reset successfully", null));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/switch-merchant/{receiverId}")
    public ResponseEntity<ApiResponse<AuthResponse>> switchMerchant(@PathVariable UUID receiverId) {
        try {
            AuthResponse response = authService.switchMerchant(receiverId);
            return ResponseEntity.ok(ApiResponse.success("Merchant switched successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}

