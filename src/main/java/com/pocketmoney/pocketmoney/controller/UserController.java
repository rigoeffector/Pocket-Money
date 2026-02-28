package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.*;
import com.pocketmoney.pocketmoney.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> createUser(@Valid @RequestBody CreateUserRequest request) {
        try {
            UserResponse response = userService.createUser(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("User created successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable UUID id) {
        try {
            UserResponse response = userService.getUserById(id);
            return ResponseEntity.ok(ApiResponse.success("User retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("User not found"));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<UserResponse>>> getAllUsers() {
        List<UserResponse> users = userService.getAllUsers();
        return ResponseEntity.ok(ApiResponse.success("Users retrieved successfully", users));
    }

    /**
     * Get all unique phone numbers from all transaction sources (paginated)
     * GET /api/users/customers
     * 
     * Query parameters:
     *   - page: Page number (default: 0)
     *   - size: Page size (default: 20)
     *   - search: Optional search term to search by phone number
     * 
     * Returns paginated list of unique phone numbers from:
     * - Transaction table (phone_number)
     * - EfasheTransaction table (customer_phone, customer_account_number, deliver_to, full_amount_phone, cashback_phone)
     */
    @GetMapping("/customers")
    public ResponseEntity<ApiResponse<PaginatedResponse<PhoneNumberResponse>>> getCustomersWithTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search) {
        try {
            PaginatedResponse<PhoneNumberResponse> response = userService.getCustomersWithTransactions(page, size, search);
            return ResponseEntity.ok(ApiResponse.success("Phone numbers retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/phone/{phoneNumber}")
    public ResponseEntity<ApiResponse<UserResponse>> getUserByPhoneNumber(@PathVariable String phoneNumber) {
        try {
            UserResponse response = userService.getUserByPhoneNumber(phoneNumber);
            return ResponseEntity.ok(ApiResponse.success("User retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("User not found with phone number: " + phoneNumber));
        }
    }

    @GetMapping("/nfc/{nfcCardId}")
    public ResponseEntity<ApiResponse<UserResponse>> getUserByNfcCardId(@PathVariable String nfcCardId) {
        try {
            UserResponse response = userService.getUserByNfcCardId(nfcCardId);
            return ResponseEntity.ok(ApiResponse.success("User retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("User not found with NFC card ID: " + nfcCardId));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        try {
            UserResponse response = userService.updateUser(id, request);
            return ResponseEntity.ok(ApiResponse.success("User updated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable UUID id) {
        try {
            userService.deleteUser(id);
            return ResponseEntity.ok(ApiResponse.success("User deleted successfully", null));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/pin")
    public ResponseEntity<ApiResponse<Void>> createPin(
            @PathVariable UUID id,
            @Valid @RequestBody CreatePinRequest request) {
        try {
            userService.createPin(id, request.getPin());
            return ResponseEntity.ok(ApiResponse.success("PIN created successfully", null));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/pin")
    public ResponseEntity<ApiResponse<Void>> updatePin(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePinRequest request) {
        try {
            userService.updatePin(id, request.getCurrentPin(), request.getNewPin());
            return ResponseEntity.ok(ApiResponse.success("PIN updated successfully", null));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/pin/reset")
    public ResponseEntity<ApiResponse<Void>> resetPin(
            @PathVariable UUID id,
            @Valid @RequestBody ResetPinRequest request) {
        try {
            userService.resetPin(id, request.getNewPin());
            return ResponseEntity.ok(ApiResponse.success("PIN reset successfully", null));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}/nfc-card")
    public ResponseEntity<ApiResponse<NfcCardResponse>> getMyNfcCard(@PathVariable UUID id) {
        try {
            NfcCardResponse response = userService.getMyNfcCard(id);
            return ResponseEntity.ok(ApiResponse.success("NFC card retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/nfc-card/assign")
    public ResponseEntity<ApiResponse<NfcCardResponse>> assignNfcCard(
            @PathVariable UUID id,
            @Valid @RequestBody AssignNfcCardRequest request) {
        try {
            NfcCardResponse response = userService.assignNfcCard(id, request);
            return ResponseEntity.ok(ApiResponse.success("NFC card assigned successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/phone/{phoneNumber}/nfc-card/assign")
    public ResponseEntity<ApiResponse<NfcCardResponse>> assignNfcCardByPhoneNumber(
            @PathVariable String phoneNumber,
            @Valid @RequestBody AssignNfcCardRequest request) {
        try {
            NfcCardResponse response = userService.assignNfcCardByPhoneNumber(phoneNumber, request);
            return ResponseEntity.ok(ApiResponse.success("NFC card assigned successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}

