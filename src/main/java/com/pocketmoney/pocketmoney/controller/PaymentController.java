package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.*;
import com.pocketmoney.pocketmoney.service.PaymentService;
import com.pocketmoney.pocketmoney.service.UserService;
import com.pocketmoney.pocketmoney.service.PdfExportService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.pocketmoney.pocketmoney.dto.PaginatedResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final UserService userService;
    private final PdfExportService pdfExportService;

    public PaymentController(PaymentService paymentService, UserService userService, PdfExportService pdfExportService) {
        this.paymentService = paymentService;
        this.userService = userService;
        this.pdfExportService = pdfExportService;
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

    @PostMapping("/merchant/top-up")
    public ResponseEntity<ApiResponse<PaymentResponse>> merchantTopUp(
            @Valid @RequestBody MerchantTopUpRequest request) {
        try {
            PaymentResponse response = paymentService.merchantTopUp(request);
            return ResponseEntity.ok(ApiResponse.success("Merchant top-up processed successfully", response));
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

    @PostMapping("/pay/momo")
    public ResponseEntity<ApiResponse<PaymentResponse>> makeMomoPayment(
            @Valid @RequestBody MomoPaymentRequest request) {
        try {
            PaymentResponse response = paymentService.makeMomoPayment(request);
            return ResponseEntity.ok(ApiResponse.success("MOMO payment initiated successfully", response));
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
    public ResponseEntity<ApiResponse<PaginatedResponse<PaymentResponse>>> getAllTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate) {
        try {
            PaginatedResponse<PaymentResponse> response = paymentService.getAllTransactions(page, size, fromDate, toDate);
            return ResponseEntity.ok(ApiResponse.success("Transactions retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
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
    public ResponseEntity<ApiResponse<ReceiverTransactionsResponse>> getTransactionsByReceiver(
            @PathVariable UUID receiverId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate) {
        try {
            ReceiverTransactionsResponse response = paymentService.getTransactionsByReceiver(receiverId, page, size, fromDate, toDate);
            return ResponseEntity.ok(ApiResponse.success("Receiver transactions retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping(value = "/transactions/receiver/{receiverId}/export", produces = "application/pdf")
    public ResponseEntity<byte[]> exportTransactionsToPdf(
            @PathVariable UUID receiverId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate) {
        try {
            // Get all transactions (no pagination for PDF export)
            ReceiverTransactionsResponse response = paymentService.getTransactionsByReceiver(receiverId, 0, Integer.MAX_VALUE, fromDate, toDate);
            
            // Get receiver information
            com.pocketmoney.pocketmoney.entity.Receiver receiver = paymentService.getReceiverEntityById(receiverId);
            
            // Generate PDF
            byte[] pdfBytes = pdfExportService.generateTransactionHistoryPdf(receiver, response, fromDate, toDate);
            
            // Set response headers
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", 
                "transaction_history_" + receiverId + "_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".pdf");
            headers.setContentLength(pdfBytes.length);
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(pdfBytes);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
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
            @RequestParam(required = false) UUID receiverId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            AdminIncomeResponse response = paymentService.getAdminIncome(fromDate, toDate, receiverId, page, size);
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

    @GetMapping("/loans/user/{userId}")
    public ResponseEntity<ApiResponse<List<LoanResponse>>> getUserLoans(@PathVariable UUID userId) {
        try {
            List<LoanResponse> loans = paymentService.getUserLoans(userId);
            return ResponseEntity.ok(ApiResponse.success("User loans retrieved successfully", loans));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/loans/merchant/{receiverId}")
    public ResponseEntity<ApiResponse<List<LoanResponse>>> getMerchantLoans(@PathVariable UUID receiverId) {
        try {
            List<LoanResponse> loans = paymentService.getMerchantLoans(receiverId);
            return ResponseEntity.ok(ApiResponse.success("Merchant loans retrieved successfully", loans));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/loans/pay")
    public ResponseEntity<ApiResponse<LoanResponse>> payLoan(
            @RequestParam("userId") UUID userId,
            @Valid @RequestBody PayLoanRequest request) {
        try {
            LoanResponse response = paymentService.payLoan(userId, request);
            return ResponseEntity.ok(ApiResponse.success("Loan payment processed successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/loans/update")
    public ResponseEntity<ApiResponse<LoanResponse>> updateLoan(
            @Valid @RequestBody UpdateLoanRequest request) {
        try {
            LoanResponse response = paymentService.updateLoan(request);
            return ResponseEntity.ok(ApiResponse.success("Loan updated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

}

