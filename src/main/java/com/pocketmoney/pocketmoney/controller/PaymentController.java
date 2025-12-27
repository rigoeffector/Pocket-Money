package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.*;
import com.pocketmoney.pocketmoney.service.PaymentService;
import com.pocketmoney.pocketmoney.service.UserService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final UserService userService;

    public PaymentController(PaymentService paymentService, UserService userService) {
        this.paymentService = paymentService;
        this.userService = userService;
    }

    @PostMapping("/top-up")
    public ResponseEntity<ApiResponse<PaymentResponse>> topUp(
            @Valid @RequestBody TopUpRequest request) {
        try {
            PaymentResponse response = paymentService.topUp(request.getNfcCardId(), request);
            return ResponseEntity.ok(ApiResponse.success("Top-up initiated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/top-up-by-phone")
    public ResponseEntity<ApiResponse<PaymentResponse>> topUpByPhone(
            @Valid @RequestBody TopUpByPhoneRequest request) {
        try {
            PaymentResponse response = paymentService.topUpByPhone(request);
            return ResponseEntity.ok(ApiResponse.success("Top-up initiated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/pay")
    public ResponseEntity<ApiResponse<PaymentResponse>> makePayment(
            @Valid @RequestBody PaymentRequest request) {
        try {
            PaymentResponse response = paymentService.makePayment(request.getUserId(), request);
            return ResponseEntity.ok(ApiResponse.success("Payment processed successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/balance/{userId}")
    public ResponseEntity<ApiResponse<BalanceResponse>> checkBalance(@PathVariable UUID userId) {
        try {
            BalanceResponse response = paymentService.checkBalance(userId);
            return ResponseEntity.ok(ApiResponse.success("Balance retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/status/{mopayTransactionId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> checkTransactionStatus(
            @PathVariable String mopayTransactionId) {
        try {
            PaymentResponse response = paymentService.checkTransactionStatus(mopayTransactionId);
            return ResponseEntity.ok(ApiResponse.success("Transaction status retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getAllTransactions() {
        List<PaymentResponse> transactions = paymentService.getAllTransactions();
        return ResponseEntity.ok(ApiResponse.success("Transactions retrieved successfully", transactions));
    }

    @GetMapping("/transactions/{transactionId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getTransactionById(@PathVariable UUID transactionId) {
        try {
            PaymentResponse response = paymentService.getTransactionById(transactionId);
            return ResponseEntity.ok(ApiResponse.success("Transaction retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/transactions/user/{userId}")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getTransactionsByUser(@PathVariable UUID userId) {
        try {
            List<PaymentResponse> transactions = paymentService.getTransactionsByUser(userId);
            return ResponseEntity.ok(ApiResponse.success("User transactions retrieved successfully", transactions));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/transactions/receiver/{receiverId}")
    public ResponseEntity<ApiResponse<ReceiverTransactionsResponse>> getTransactionsByReceiver(@PathVariable UUID receiverId) {
        try {
            ReceiverTransactionsResponse response = paymentService.getTransactionsByReceiver(receiverId);
            return ResponseEntity.ok(ApiResponse.success("Receiver transactions retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/transactions/main-merchant/{mainReceiverId}/all")
    public ResponseEntity<ApiResponse<PaginatedResponse<PaymentResponse>>> getAllTransactionsForMainMerchant(
            @PathVariable UUID mainReceiverId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate) {
        try {
            // Set time to start/end of day if only date is provided
            if (fromDate != null && fromDate.getHour() == 0 && fromDate.getMinute() == 0 && fromDate.getSecond() == 0) {
                fromDate = fromDate.withHour(0).withMinute(0).withSecond(0);
            }
            if (toDate != null) {
                toDate = toDate.withHour(23).withMinute(59).withSecond(59);
            }
            
            PaginatedResponse<PaymentResponse> response = paymentService.getAllTransactionsForMainMerchant(
                    mainReceiverId, page, size, search, fromDate, toDate);
            return ResponseEntity.ok(ApiResponse.success("All transactions for main merchant and submerchants retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/transactions/main-merchant/{mainReceiverId}/submerchant/{submerchantId}")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getTransactionsForSubmerchant(
            @PathVariable UUID mainReceiverId,
            @PathVariable UUID submerchantId) {
        try {
            List<PaymentResponse> transactions = paymentService.getTransactionsForSubmerchant(mainReceiverId, submerchantId);
            return ResponseEntity.ok(ApiResponse.success("Submerchant transactions retrieved successfully", transactions));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/bonus-history/{userId}")
    public ResponseEntity<ApiResponse<List<UserBonusHistoryResponse>>> getUserBonusHistory(@PathVariable UUID userId) {
        try {
            List<UserBonusHistoryResponse> history = paymentService.getUserBonusHistory(userId);
            return ResponseEntity.ok(ApiResponse.success("User bonus history retrieved successfully", history));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/cards/{nfcCardId}")
    public ResponseEntity<ApiResponse<CardDetailsResponse>> getCardDetails(@PathVariable String nfcCardId) {
        try {
            CardDetailsResponse response = userService.getCardDetailsByNfcCardId(nfcCardId);
            return ResponseEntity.ok(ApiResponse.success("Card details retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/admin-income")
    public ResponseEntity<ApiResponse<AdminIncomeResponse>> getAdminIncome(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(required = false) UUID receiverId) {
        try {
            AdminIncomeResponse response = paymentService.getAdminIncome(fromDate, toDate, receiverId);
            return ResponseEntity.ok(ApiResponse.success("Admin income retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/admin/dashboard-statistics")
    public ResponseEntity<ApiResponse<AdminDashboardStatisticsResponse>> getAdminDashboardStatistics() {
        try {
            AdminDashboardStatisticsResponse response = paymentService.getAdminDashboardStatistics();
            return ResponseEntity.ok(ApiResponse.success("Dashboard statistics retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

}

