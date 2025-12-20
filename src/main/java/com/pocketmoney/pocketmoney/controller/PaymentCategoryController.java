package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.CreatePaymentCategoryRequest;
import com.pocketmoney.pocketmoney.dto.PaymentCategoryResponse;
import com.pocketmoney.pocketmoney.dto.UpdatePaymentCategoryRequest;
import com.pocketmoney.pocketmoney.service.PaymentCategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment-categories")
public class PaymentCategoryController {

    private final PaymentCategoryService paymentCategoryService;

    public PaymentCategoryController(PaymentCategoryService paymentCategoryService) {
        this.paymentCategoryService = paymentCategoryService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentCategoryResponse>> createPaymentCategory(@Valid @RequestBody CreatePaymentCategoryRequest request) {
        try {
            PaymentCategoryResponse response = paymentCategoryService.createPaymentCategory(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("Payment category created successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PaymentCategoryResponse>> getPaymentCategoryById(@PathVariable UUID id) {
        try {
            PaymentCategoryResponse response = paymentCategoryService.getPaymentCategoryById(id);
            return ResponseEntity.ok(ApiResponse.success("Payment category retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PaymentCategoryResponse>>> getAllPaymentCategories() {
        List<PaymentCategoryResponse> categories = paymentCategoryService.getAllPaymentCategories();
        return ResponseEntity.ok(ApiResponse.success("Payment categories retrieved successfully", categories));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<PaymentCategoryResponse>>> getActivePaymentCategories() {
        List<PaymentCategoryResponse> categories = paymentCategoryService.getActivePaymentCategories();
        return ResponseEntity.ok(ApiResponse.success("Active payment categories retrieved successfully", categories));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<PaymentCategoryResponse>> updatePaymentCategory(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePaymentCategoryRequest request) {
        try {
            PaymentCategoryResponse response = paymentCategoryService.updatePaymentCategory(id, request);
            return ResponseEntity.ok(ApiResponse.success("Payment category updated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deletePaymentCategory(@PathVariable UUID id) {
        try {
            paymentCategoryService.deletePaymentCategory(id);
            return ResponseEntity.ok(ApiResponse.success("Payment category deleted successfully", null));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}
