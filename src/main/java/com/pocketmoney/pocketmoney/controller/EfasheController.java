package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.EfasheInitiateRequest;
import com.pocketmoney.pocketmoney.dto.EfasheInitiateForOtherRequest;
import com.pocketmoney.pocketmoney.dto.EfasheInitiateResponse;
import com.pocketmoney.pocketmoney.dto.EfasheRefundRequest;
import com.pocketmoney.pocketmoney.dto.EfasheRefundHistoryResponse;
import com.pocketmoney.pocketmoney.dto.EfasheStatusResponse;
import com.pocketmoney.pocketmoney.dto.EfasheTransactionResponse;
import com.pocketmoney.pocketmoney.dto.ElectricityTokensResponse;
import com.pocketmoney.pocketmoney.dto.PaginatedResponse;
import com.pocketmoney.pocketmoney.dto.PaymentResponse;
import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import com.pocketmoney.pocketmoney.service.EfashePaymentService;
import com.pocketmoney.pocketmoney.service.PaymentService;
import com.pocketmoney.pocketmoney.service.PdfExportService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/efashe")
public class EfasheController {

    private static final Logger logger = LoggerFactory.getLogger(EfasheController.class);

    private final EfashePaymentService efashePaymentService;
    private final PdfExportService pdfExportService;
    private final PaymentService paymentService;

    public EfasheController(EfashePaymentService efashePaymentService, PdfExportService pdfExportService, PaymentService paymentService) {
        this.efashePaymentService = efashePaymentService;
        this.pdfExportService = pdfExportService;
        this.paymentService = paymentService;
    }

    /**
     * Initiate EFASHE payment with MoPay
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
     * Initiate EFASHE payment for others (buying airtime for another person)
     * POST /api/efashe/initiate-for-other
     * 
     * This endpoint allows buying airtime for another person:
     * - phone: used for MoPay debit (the person paying)
     * - anotherPhoneNumber: used for EFASHE validate (the person receiving airtime)
     * 
     * Only works for AIRTIME service type
     */
    @PostMapping("/initiate-for-other")
    public ResponseEntity<ApiResponse<EfasheInitiateResponse>> initiatePaymentForOther(
            @Valid @RequestBody EfasheInitiateForOtherRequest request) {
        try {
            // Validate that service type is AIRTIME
            if (request.getServiceType() != EfasheServiceType.AIRTIME) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error("This endpoint only supports AIRTIME service type"));
            }
            
            EfasheInitiateResponse response = efashePaymentService.initiatePaymentForOther(request);
            return ResponseEntity.ok(ApiResponse.success("EFASHE payment initiated successfully for other person", response));
        } catch (RuntimeException e) {
            logger.error("Error initiating EFASHE payment for other: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Process a validated transaction using the configured provider
     * POST /api/efashe/process/{transactionId}
     * Updates validated flag to "PROCESS" and initiates payment
     */
    @PostMapping("/process/{transactionId}")
    public ResponseEntity<ApiResponse<EfasheInitiateResponse>> processPayment(
            @PathVariable("transactionId") String transactionId) {
        try {
            EfasheInitiateResponse response = efashePaymentService.processPayment(transactionId);
            return ResponseEntity.ok(ApiResponse.success("EFASHE payment processed successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error processing EFASHE payment: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Check EFASHE transaction status using the configured provider transaction ID
     * GET/POST /api/efashe/status/{transactionId}
     * Automatically triggers EFASHE validate and execute when payment status is SUCCESS
     * Supports both GET and POST methods for flexibility
     */
    @GetMapping("/status/{transactionId}")
    @PostMapping("/status/{transactionId}")
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
     *   - serviceType: Optional filter by service type (AIRTIME, RRA, TV, MTN, ELECTRICITY, GASOLINE, DIESEL, or ALL to show all service types)
     *   - phone: Optional filter by customer phone number (accepts any format, will be normalized)
     *            - For ADMIN/RECEIVER: optional filter
     *            - For USER: ignored, automatically uses their own phone number
     *   - search: Optional search term to search across multiple transaction fields
     *            - Searches in: customerPhone, customerAccountNumber, customerAccountName, 
     *                          transactionId, mopayTransactionId, trxId, token, amount, 
     *                          serviceType, mopayStatus, efasheStatus
     *            - Case-insensitive partial match
     *            - Examples: phone "250788123456", account "0788123456", name "MUHINZI", 
     *                       amount "1000", token "1234-5678-9012", transaction ID "CMN123", 
     *                       service type "AIRTIME", status "SUCCESS"
     *   - page: Page number (default: 0, must be >= 0)
     *   - size: Page size (default: 20, must be between 1 and 200)
     *   - fromDate: Optional start date filter (ISO 8601 format)
     *   - toDate: Optional end date filter (ISO 8601 format)
     * 
     * Examples:
     *   ADMIN: /api/efashe/transactions?serviceType=RRA&phone=250784638201
     *   ADMIN: /api/efashe/transactions?serviceType=ALL&phone=250794230137&fromDate=2026-01-16T00:00:00&toDate=2026-01-16T23:59:59 (shows all service types)
     *   ADMIN: /api/efashe/transactions (returns all transactions)
     *   RECEIVER: /api/efashe/transactions?serviceType=AIRTIME&phone=250784638201
     *   RECEIVER: /api/efashe/transactions?serviceType=ALL (shows all service types)
     *   USER: /api/efashe/transactions?serviceType=RRA (returns only user's transactions)
     *   USER: /api/efashe/transactions?serviceType=ALL (returns all service types for user's transactions)
     */
    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<PaginatedResponse<EfasheTransactionResponse>>> getTransactions(
            @RequestParam(value = "serviceType", required = false) String serviceTypeParam,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "fromDate", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(value = "toDate", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate) {
        try {
            // Validate pagination parameters
            if (page < 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error("Page number must be 0 or greater"));
            }
            if (size < 1) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error("Page size must be at least 1"));
            }
            // Limit maximum page size to prevent performance issues
            int maxPageSize = 200;
            if (size > maxPageSize) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error("Page size cannot exceed " + maxPageSize + ". Please use a smaller page size."));
            }
            // Check if serviceType is PAY_CUSTOMER (special case for ADMIN only)
            if (serviceTypeParam != null && "PAY_CUSTOMER".equalsIgnoreCase(serviceTypeParam.trim())) {
                // Verify user is ADMIN
                org.springframework.security.core.Authentication authentication = 
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (authentication == null || !authentication.isAuthenticated()) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(ApiResponse.error("Authentication required"));
                }
                
                boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
                
                if (!isAdmin) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(ApiResponse.error("Only ADMIN users can access PAY_CUSTOMER transactions via this endpoint"));
                }
                
                // Get transactions by payment category
                PaginatedResponse<PaymentResponse> paymentResponse = paymentService.getAllTransactionsByPaymentCategory(
                    "PAY_CUSTOMER", page, size, search, fromDate, toDate);
                
                // Map PaymentResponse to EfasheTransactionResponse
                List<EfasheTransactionResponse> efasheTransactions = paymentResponse.getContent().stream()
                    .map(payment -> {
                        EfasheTransactionResponse efashe = new EfasheTransactionResponse();
                        efashe.setId(payment.getId());
                        efashe.setTransactionId(payment.getMopayTransactionId() != null ? payment.getMopayTransactionId() : payment.getId().toString());
                        efashe.setServiceType(null); // PAY_CUSTOMER is not an EFASHE service type
                        efashe.setCustomerPhone(payment.getPayerPhone() != null ? payment.getPayerPhone() : 
                            (payment.getUser() != null ? payment.getUser().getPhoneNumber() : null));
                        efashe.setCustomerAccountNumber(payment.getPayerPhone() != null ? payment.getPayerPhone() : "");
                        efashe.setCustomerAccountName(payment.getUser() != null ? payment.getUser().getFullNames() : null);
                        efashe.setAmount(payment.getAmount());
                        efashe.setCurrency("RWF");
                        efashe.setTrxId(null);
                        efashe.setMopayTransactionId(payment.getMopayTransactionId());
                        efashe.setMopayStatus(payment.getStatus() != null ? payment.getStatus().name() : null);
                        efashe.setEfasheStatus(null);
                        efashe.setMessage("PAY_CUSTOMER transaction");
                        efashe.setCreatedAt(payment.getCreatedAt());
                        efashe.setUpdatedAt(payment.getCreatedAt());
                        return efashe;
                    })
                    .collect(java.util.stream.Collectors.toList());
                
                PaginatedResponse<EfasheTransactionResponse> response = new PaginatedResponse<>();
                response.setContent(efasheTransactions);
                response.setCurrentPage(paymentResponse.getCurrentPage());
                response.setPageSize(paymentResponse.getPageSize());
                response.setTotalElements(paymentResponse.getTotalElements());
                response.setTotalPages(paymentResponse.getTotalPages());
                response.setFirst(paymentResponse.isFirst());
                response.setLast(paymentResponse.isLast());
                
                return ResponseEntity.ok(ApiResponse.success("PAY_CUSTOMER transactions retrieved successfully", response));
            }
            
            // Check if serviceType is GASOLINE or DIESEL - these come from transactions table (same source as Daily Report)
            // NOT from efashe_transactions, since fuel payments use payment_category not EFASHE service_type
            if (serviceTypeParam != null) {
                String serviceTypeStr = serviceTypeParam.trim().toUpperCase();
                if ("GASOLINE".equals(serviceTypeStr) || "DIESEL".equals(serviceTypeStr)) {
                    PaginatedResponse<PaymentResponse> paymentResponse = paymentService.getTransactionsByPaymentCategoryForFuel(
                        serviceTypeStr, page, size, search, fromDate, toDate, phone);
                    
                    EfasheServiceType fuelServiceType = "GASOLINE".equals(serviceTypeStr) 
                        ? EfasheServiceType.GASOLINE : EfasheServiceType.DIESEL;
                    
                    List<EfasheTransactionResponse> efasheTransactions = paymentResponse.getContent().stream()
                        .map(payment -> {
                            EfasheTransactionResponse efashe = new EfasheTransactionResponse();
                            efashe.setId(payment.getId());
                            efashe.setTransactionId(payment.getMopayTransactionId() != null ? payment.getMopayTransactionId() : payment.getId().toString());
                            efashe.setServiceType(fuelServiceType);
                            efashe.setCustomerPhone(payment.getPayerPhone() != null ? payment.getPayerPhone() : 
                                (payment.getUser() != null ? payment.getUser().getPhoneNumber() : null));
                            efashe.setCustomerAccountNumber(payment.getPayerPhone() != null ? payment.getPayerPhone() : "");
                            efashe.setCustomerAccountName(payment.getUser() != null ? payment.getUser().getFullNames() : null);
                            efashe.setAmount(payment.getAmount());
                            efashe.setCurrency("RWF");
                            efashe.setTrxId(null);
                            efashe.setMopayTransactionId(payment.getMopayTransactionId());
                            efashe.setMopayStatus(payment.getStatus() != null ? payment.getStatus().name() : null);
                            efashe.setEfasheStatus("SUCCESS");
                            efashe.setMessage(serviceTypeStr + " payment");
                            efashe.setCreatedAt(payment.getCreatedAt());
                            efashe.setUpdatedAt(payment.getCreatedAt());
                            return efashe;
                        })
                        .collect(Collectors.toList());
                    
                    PaginatedResponse<EfasheTransactionResponse> fuelResponse = new PaginatedResponse<>();
                    fuelResponse.setContent(efasheTransactions);
                    fuelResponse.setCurrentPage(paymentResponse.getCurrentPage());
                    fuelResponse.setPageSize(paymentResponse.getPageSize());
                    fuelResponse.setTotalElements(paymentResponse.getTotalElements());
                    fuelResponse.setTotalPages(paymentResponse.getTotalPages());
                    fuelResponse.setFirst(paymentResponse.isFirst());
                    fuelResponse.setLast(paymentResponse.isLast());
                    
                    return ResponseEntity.ok(ApiResponse.success(serviceTypeStr + " transactions retrieved successfully (from Daily Report data source)", fuelResponse));
                }
            }
            
            // Parse serviceType parameter - allow "ALL" to show all service types
            EfasheServiceType serviceType = null;
            if (serviceTypeParam != null && !serviceTypeParam.trim().isEmpty()) {
                String serviceTypeStr = serviceTypeParam.trim().toUpperCase();
                if (!"ALL".equals(serviceTypeStr)) {
                    try {
                        serviceType = EfasheServiceType.valueOf(serviceTypeStr);
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(ApiResponse.error("Invalid serviceType: " + serviceTypeParam + ". Valid values are: AIRTIME, RRA, TV, MTN, ELECTRICITY, GASOLINE, DIESEL, PAY_CUSTOMER (ADMIN only), or ALL"));
                    }
                }
                // If "ALL", serviceType remains null, which means no filter will be applied
            }
            
            PaginatedResponse<EfasheTransactionResponse> response = efashePaymentService.getTransactions(
                serviceType, phone, search, page, size, fromDate, toDate);
            return ResponseEntity.ok(ApiResponse.success("EFASHE transactions retrieved successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error retrieving EFASHE transactions: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Get electricity tokens for a meter number (test endpoint)
     * GET /api/efashe/electricity/tokens?meterNo={meterNumber}&numTokens={numTokens}
     * 
     * Query parameters:
     *   - meterNo: Meter number (required) - e.g., "0215006303691"
     *   - numTokens: Number of tokens to retrieve (optional, default: 1)
     * 
     * Example:
     *   GET /api/efashe/electricity/tokens?meterNo=0215006303691&numTokens=1
     */
    @GetMapping("/electricity/tokens")
    public ResponseEntity<ApiResponse<ElectricityTokensResponse>> getElectricityTokens(
            @RequestParam("meterNo") String meterNo,
            @RequestParam(value = "numTokens", required = false, defaultValue = "1") Integer numTokens) {
        try {
            if (meterNo == null || meterNo.trim().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error("meterNo parameter is required"));
            }
            
            ElectricityTokensResponse response = efashePaymentService.getElectricityTokens(meterNo.trim(), numTokens);
            return ResponseEntity.ok(ApiResponse.success("Electricity tokens retrieved successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error retrieving electricity tokens: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Export PDF receipt for successful RRA, TV, or ELECTRICITY transactions
     * GET /api/efashe/receipt/{transactionId}
     * 
     * Only works for:
     *   - RRA, TV, and ELECTRICITY service types
     *   - SUCCESS transactions (both MoPay and EFASHE status must be SUCCESS)
     */
    @GetMapping("/receipt/{transactionId}")
    public ResponseEntity<byte[]> exportReceipt(@PathVariable("transactionId") String transactionId) {
        try {
            // Get transaction by ID (tries both EFASHE and MoPay transaction IDs)
            var transactionOpt = efashePaymentService.findTransactionById(transactionId);
            
            if (!transactionOpt.isPresent()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            
            var transaction = transactionOpt.get();
            
            // Validate service type - only RRA, TV, and ELECTRICITY are allowed
            if (transaction.getServiceType() != EfasheServiceType.RRA && 
                transaction.getServiceType() != EfasheServiceType.TV &&
                transaction.getServiceType() != EfasheServiceType.ELECTRICITY) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            
            // Validate transaction status - must be SUCCESS for both MoPay and EFASHE
            String mopayStatus = transaction.getMopayStatus();
            String efasheStatus = transaction.getEfasheStatus();
            
            boolean isSuccess = (mopayStatus != null && ("200".equals(mopayStatus) || "201".equals(mopayStatus) || "SUCCESS".equalsIgnoreCase(mopayStatus)))
                && (efasheStatus != null && "SUCCESS".equalsIgnoreCase(efasheStatus));
            
            if (!isSuccess) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            
            // Generate PDF receipt
            byte[] pdfBytes = pdfExportService.generateEfasheReceiptPdf(transaction);
            
            // Set response headers
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
            
            String filename = "receipt_" + transaction.getServiceType().toString().toLowerCase() + "_" + 
                transactionId + "_" + 
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + ".pdf";
            
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(pdfBytes.length);
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(pdfBytes);
        } catch (RuntimeException e) {
            logger.error("Error exporting EFASHE receipt: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Process refund for an EFASHE transaction
     * POST /api/efashe/refund/{transactionId}
     * 
     * Refunds the amount paid minus cashback (customer cashback + besoft share)
     * Transfers from adminPhone (DEBIT) to receiverPhone (CREDIT)
     * 
     * Request body:
     *   - adminPhone: Required - Admin phone number (DEBIT - who pays for refund)
     *   - receiverPhone: Required - Customer phone number (CREDIT - who receives refund)
     *   - message: Optional - Custom message for the refund
     * 
     * Example:
     *   POST /api/efashe/refund/EFASHE1768575599499902855
     *   {
     *     "adminPhone": "250784638201",
     *     "receiverPhone": "250784638201",
     *     "message": "Refund for failed transaction"
     *   }
     */
    @PostMapping("/refund/{transactionId}")
    public ResponseEntity<ApiResponse<String>> processRefund(
            @PathVariable("transactionId") String transactionId,
            @Valid @RequestBody EfasheRefundRequest request) {
        try {
            String result = efashePaymentService.processRefund(transactionId, request);
            return ResponseEntity.ok(ApiResponse.success("Refund processed successfully", result));
        } catch (RuntimeException e) {
            logger.error("Error processing refund: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Legacy alias for processing a validated transaction.
     * POST /api/efashe/bizao/process/{transactionId}
     * Uses the configured provider from application properties.
     */
    @PostMapping("/bizao/process/{transactionId}")
    public ResponseEntity<ApiResponse<EfasheInitiateResponse>> processPaymentLegacy(
            @PathVariable("transactionId") String transactionId) {
        try {
            EfasheInitiateResponse response = efashePaymentService.processPayment(transactionId);
            return ResponseEntity.ok(ApiResponse.success("EFASHE payment processed successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error processing EFASHE payment (legacy endpoint): ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Legacy alias for checking transaction status.
     * GET/POST /api/efashe/bizao/status/{transactionId}
     * Uses the configured provider from application properties.
     */
    @GetMapping("/bizao/status/{transactionId}")
    @PostMapping("/bizao/status/{transactionId}")
    public ResponseEntity<ApiResponse<EfasheStatusResponse>> checkTransactionStatusLegacy(
            @PathVariable("transactionId") String transactionId) {
        try {
            EfasheStatusResponse response = efashePaymentService.checkTransactionStatus(transactionId);
            return ResponseEntity.ok(ApiResponse.success("Transaction status retrieved successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error checking EFASHE transaction status (legacy endpoint): ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }


    /**
     * Get refund history with optional filtering
     * GET /api/efashe/refund-history
     * 
     * Query parameters:
     *   - receiverPhone: Optional filter by receiver phone number
     *   - status: Optional filter by status (PENDING, SUCCESS, FAILED)
     *   - fromDate: Optional start date filter (ISO 8601 format)
     *   - toDate: Optional end date filter (ISO 8601 format)
     *   - page: Page number (default: 0, must be >= 0)
     *   - size: Page size (default: 20, must be between 1 and 200)
     * 
     * Examples:
     *   GET /api/efashe/refund-history
     *   GET /api/efashe/refund-history?receiverPhone=250784638201
     *   GET /api/efashe/refund-history?status=SUCCESS
     *   GET /api/efashe/refund-history?receiverPhone=250784638201&status=SUCCESS&fromDate=2026-01-16T00:00:00&toDate=2026-01-16T23:59:59&page=0&size=20
     */
    @GetMapping("/refund-history")
    public ResponseEntity<ApiResponse<PaginatedResponse<EfasheRefundHistoryResponse>>> getRefundHistory(
            @RequestParam(value = "receiverPhone", required = false) String receiverPhone,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "fromDate", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(value = "toDate", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        try {
            // Validate pagination parameters
            if (page < 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error("Page number must be 0 or greater"));
            }
            if (size < 1) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error("Page size must be at least 1"));
            }
            // Limit maximum page size to prevent performance issues
            int maxPageSize = 200;
            if (size > maxPageSize) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error("Page size cannot exceed " + maxPageSize + ". Please use a smaller page size."));
            }
            
            PaginatedResponse<EfasheRefundHistoryResponse> response =
                efashePaymentService.getRefundHistory(receiverPhone, status, fromDate, toDate, page, size);
            return ResponseEntity.ok(ApiResponse.success("Refund history retrieved successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error retrieving refund history: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}
