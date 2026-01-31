package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.CreateReceiverRequest;
import com.pocketmoney.pocketmoney.dto.CreateUserRequest;
import com.pocketmoney.pocketmoney.dto.ReceiverLoginRequest;
import com.pocketmoney.pocketmoney.dto.PaymentResponse;
import com.pocketmoney.pocketmoney.dto.PublicMomoPaymentRequest;
import com.pocketmoney.pocketmoney.dto.ReceiverLoginResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverResponse;
import com.pocketmoney.pocketmoney.dto.UserLoginRequest;
import com.pocketmoney.pocketmoney.dto.UserLoginResponse;
import com.pocketmoney.pocketmoney.dto.UserResponse;
import com.pocketmoney.pocketmoney.service.PaymentService;
import com.pocketmoney.pocketmoney.service.ReceiverService;
import com.pocketmoney.pocketmoney.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final UserService userService;
    private final ReceiverService receiverService;
    private final PaymentService paymentService;

    public PublicController(UserService userService, ReceiverService receiverService, PaymentService paymentService) {
        this.userService = userService;
        this.receiverService = receiverService;
        this.paymentService = paymentService;
    }

    @PostMapping("/users/signup")
    public ResponseEntity<ApiResponse<UserResponse>> signUpUser(@Valid @RequestBody CreateUserRequest request) {
        try {
            UserResponse response = userService.createUser(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("User signed up successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/receivers/signup")
    public ResponseEntity<ApiResponse<ReceiverResponse>> signUpReceiver(@Valid @RequestBody CreateReceiverRequest request) {
        try {
            ReceiverResponse response = receiverService.createReceiver(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("Receiver signed up successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/users/login")
    public ResponseEntity<ApiResponse<UserLoginResponse>> loginUser(@Valid @RequestBody UserLoginRequest request) {
        try {
            UserLoginResponse response = userService.login(request.getPhoneNumber(), request.getPin());
            return ResponseEntity.ok(ApiResponse.success("Login successful", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/receivers/login")
    public ResponseEntity<ApiResponse<ReceiverLoginResponse>> loginReceiver(@Valid @RequestBody ReceiverLoginRequest request) {
        try {
            ReceiverLoginResponse response = receiverService.login(request.getUsername(), request.getPassword());
            return ResponseEntity.ok(ApiResponse.success("Login successful", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Public MOMO payment endpoint - no authentication required
     * POST /api/public/payments/pay/momo
     * 
     * Request body:
     * {
     *   "phoneNumber": "250781234567",  // Required - Payer phone number
     *   "paymentCategoryId": "uuid",      // Required
     *   "amount": 1000.00,               // Required
     *   "receiverId": "uuid",            // Required
     *   "receiverPhone": "250794230137", // Optional
     *   "message": "Payment message"     // Optional
     * }
     * 
     * Note: This endpoint does not require authentication. It's designed for public use,
     * such as when a user scans a QR code and needs to make a payment.
     */
    @PostMapping("/payments/pay/momo")
    public ResponseEntity<ApiResponse<PaymentResponse>> makePublicMomoPayment(
            @Valid @RequestBody PublicMomoPaymentRequest request) {
        try {
            PaymentResponse response = paymentService.makePublicMomoPayment(request);
            return ResponseEntity.ok(ApiResponse.success("MOMO payment initiated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}

