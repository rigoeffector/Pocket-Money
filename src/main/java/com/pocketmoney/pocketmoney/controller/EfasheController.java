package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.EfasheInitiateRequest;
import com.pocketmoney.pocketmoney.dto.EfasheInitiateResponse;
import com.pocketmoney.pocketmoney.dto.EfasheStatusResponse;
import com.pocketmoney.pocketmoney.dto.EfasheTransactionResponse;
import com.pocketmoney.pocketmoney.dto.PaginatedResponse;
import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import com.pocketmoney.pocketmoney.service.EfashePaymentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/efashe")
public class EfasheController {

    private static final Logger logger = LoggerFactory.getLogger(EfasheController.class);

    private final EfashePaymentService efashePaymentService;

    public EfasheController(EfashePaymentService efashePaymentService) {
        this.efashePaymentService = efashePaymentService;
    }

    /**
     * Initiate EFASHE payment with MoPay (Admin only)
     * POST /api/efashe/initiate
     */
    @PostMapping("/initiate")
    public ResponseEntity<ApiResponse<EfasheInitiateResponse>> initiatePayment(
            @Valid @RequestBody EfasheInitiateRequest request) {
        try {
            EfasheInitiateResponse response = efashePaymentService.initiatePayment(request);
            return ResponseEntity.ok(ApiResponse.success("EFASHE payment initiated successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error initiating EFASHE payment: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Check EFASHE transaction status using MoPay transaction ID
     * GET /api/efashe/status/{transactionId}
     * Automatically triggers EFASHE validate and execute when MoPay status is SUCCESS
     */
    @GetMapping("/status/{transactionId}")
    public ResponseEntity<ApiResponse<EfasheStatusResponse>> checkTransactionStatus(
            @PathVariable("transactionId") String transactionId) {
        try {
            EfasheStatusResponse response = efashePaymentService.checkTransactionStatus(transactionId);
            return ResponseEntity.ok(ApiResponse.success("Transaction status retrieved successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error checking EFASHE transaction status: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Get list of verticals from EFASHE API
     * GET /api/efashe/verticals
     * Uses same authentication as validate endpoint
     */
    @GetMapping("/verticals")
    public ResponseEntity<ApiResponse<Object>> getVerticals() {
        try {
            Object verticals = efashePaymentService.getVerticals();
            return ResponseEntity.ok(ApiResponse.success("EFASHE verticals retrieved successfully", verticals));
        } catch (RuntimeException e) {
            logger.error("Error retrieving EFASHE verticals: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Get EFASHE transactions with optional filtering by service type, phone number, and date range
     * GET /api/efashe/transactions
     * 
     * Access Control:
     *   - ADMIN: Can see all transactions (optional phone filter available)
     *   - RECEIVER: Can see all transactions (optional phone filter available)
     *   - USER: Can only see their own transactions (automatically filtered by their phone number)
     * 
     * Query parameters:
     *   - serviceType: Optional filter by service type (AIRTIME, RRA, TV, MTN)
     *   - phone: Optional filter by customer phone number (accepts any format, will be normalized)
     *            - For ADMIN/RECEIVER: optional filter
     *            - For USER: ignored, automatically uses their own phone number
     *   - page: Page number (default: 0)
     *   - size: Page size (default: 20)
     *   - fromDate: Optional start date filter (ISO 8601 format)
     *   - toDate: Optional end date filter (ISO 8601 format)
     * 
     * Examples:
     *   ADMIN: /api/efashe/transactions?serviceType=RRA&phone=250784638201
     *   ADMIN: /api/efashe/transactions (returns all transactions)
     *   RECEIVER: /api/efashe/transactions?serviceType=AIRTIME&phone=250784638201
     *   RECEIVER: /api/efashe/transactions (returns all transactions)
     *   USER: /api/efashe/transactions?serviceType=RRA (returns only user's transactions)
     *   USER: /api/efashe/transactions?phone=250784638201 (phone parameter ignored, returns only user's transactions)
     */
    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<PaginatedResponse<EfasheTransactionResponse>>> getTransactions(
            @RequestParam(value = "serviceType", required = false) EfasheServiceType serviceType,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "fromDate", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(value = "toDate", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate) {
        try {
            PaginatedResponse<EfasheTransactionResponse> response = efashePaymentService.getTransactions(
                serviceType, phone, page, size, fromDate, toDate);
            return ResponseEntity.ok(ApiResponse.success("EFASHE transactions retrieved successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error retrieving EFASHE transactions: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}
