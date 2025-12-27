package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.ApproveBalanceAssignmentRequest;
import com.pocketmoney.pocketmoney.dto.AssignBalanceRequest;
import com.pocketmoney.pocketmoney.dto.BalanceAssignmentHistoryResponse;
import com.pocketmoney.pocketmoney.dto.CreateReceiverRequest;
import com.pocketmoney.pocketmoney.dto.CreateSubmerchantRequest;
import com.pocketmoney.pocketmoney.dto.ReceiverAnalyticsResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverDashboardResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverWalletResponse;
import com.pocketmoney.pocketmoney.dto.ResetPasswordRequest;
import com.pocketmoney.pocketmoney.dto.UpdateReceiverRequest;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import com.pocketmoney.pocketmoney.service.ReceiverService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/receivers")
public class ReceiverController {

    private final ReceiverService receiverService;

    public ReceiverController(ReceiverService receiverService) {
        this.receiverService = receiverService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ReceiverResponse>> createReceiver(@Valid @RequestBody CreateReceiverRequest request) {
        try {
            ReceiverResponse response = receiverService.createReceiver(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("Receiver created successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/phone/{phone}")
    public ResponseEntity<ApiResponse<ReceiverResponse>> getReceiverByPhone(@PathVariable String phone) {
        try {
            ReceiverResponse response = receiverService.getReceiverByPhone(phone);
            return ResponseEntity.ok(ApiResponse.success("Receiver retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("Receiver not found with phone: " + phone));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ReceiverResponse>>> getAllReceivers() {
        List<ReceiverResponse> receivers = receiverService.getAllReceivers();
        return ResponseEntity.ok(ApiResponse.success("Receivers retrieved successfully", receivers));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<ReceiverResponse>>> getActiveReceivers() {
        List<ReceiverResponse> receivers = receiverService.getActiveReceivers();
        return ResponseEntity.ok(ApiResponse.success("Active receivers retrieved successfully", receivers));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<ReceiverResponse>>> getReceiversByStatus(@PathVariable ReceiverStatus status) {
        List<ReceiverResponse> receivers = receiverService.getReceiversByStatus(status);
        return ResponseEntity.ok(ApiResponse.success("Receivers retrieved successfully", receivers));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ReceiverResponse>> updateReceiver(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateReceiverRequest request) {
        try {
            ReceiverResponse response = receiverService.updateReceiver(id, request);
            return ResponseEntity.ok(ApiResponse.success("Receiver updated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/suspend")
    public ResponseEntity<ApiResponse<ReceiverResponse>> suspendReceiver(@PathVariable UUID id) {
        try {
            ReceiverResponse response = receiverService.suspendReceiver(id);
            return ResponseEntity.ok(ApiResponse.success("Receiver suspended successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<ReceiverResponse>> activateReceiver(@PathVariable UUID id) {
        try {
            ReceiverResponse response = receiverService.activateReceiver(id);
            return ResponseEntity.ok(ApiResponse.success("Receiver activated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteReceiver(@PathVariable UUID id) {
        try {
            receiverService.deleteReceiver(id);
            return ResponseEntity.ok(ApiResponse.success("Receiver deleted successfully", null));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}/wallet")
    public ResponseEntity<ApiResponse<ReceiverWalletResponse>> getReceiverWallet(@PathVariable UUID id) {
        try {
            ReceiverWalletResponse response = receiverService.getReceiverWallet(id);
            return ResponseEntity.ok(ApiResponse.success("Receiver wallet retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody ResetPasswordRequest request) {
        try {
            receiverService.resetPassword(id, request.getNewPassword());
            return ResponseEntity.ok(ApiResponse.success("Receiver password reset successfully", null));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}/analytics")
    public ResponseEntity<ApiResponse<ReceiverAnalyticsResponse>> getReceiverAnalytics(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) UUID categoryId) {
        try {
            ReceiverAnalyticsResponse response = receiverService.getReceiverAnalytics(id, fromDate, toDate, year, categoryId);
            return ResponseEntity.ok(ApiResponse.success("Receiver analytics retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}/balance-history")
    public ResponseEntity<ApiResponse<List<BalanceAssignmentHistoryResponse>>> getBalanceAssignmentHistory(@PathVariable UUID id) {
        try {
            List<BalanceAssignmentHistoryResponse> history = receiverService.getBalanceAssignmentHistory(id);
            return ResponseEntity.ok(ApiResponse.success("Balance assignment history retrieved successfully", history));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{receiverId}/balance-history/{historyId}/approve")
    public ResponseEntity<ApiResponse<BalanceAssignmentHistoryResponse>> approveBalanceAssignment(
            @PathVariable UUID receiverId,
            @PathVariable UUID historyId,
            @Valid @RequestBody ApproveBalanceAssignmentRequest request) {
        try {
            BalanceAssignmentHistoryResponse response = receiverService.approveBalanceAssignment(receiverId, historyId, request.isApprove());
            String message = request.isApprove() ? "Balance assignment approved successfully" : "Balance assignment rejected successfully";
            return ResponseEntity.ok(ApiResponse.success(message, response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ReceiverResponse>> getReceiverById(@PathVariable UUID id) {
        try {
            ReceiverResponse response = receiverService.getReceiverById(id);
            return ResponseEntity.ok(ApiResponse.success("Receiver retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/assign-balance")
    public ResponseEntity<ApiResponse<BalanceAssignmentHistoryResponse>> assignBalance(
            @PathVariable UUID id,
            @Valid @RequestBody AssignBalanceRequest request) {
        try {
            BalanceAssignmentHistoryResponse response = receiverService.assignBalance(id, request);
            return ResponseEntity.ok(ApiResponse.success("Balance assignment initiated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}/dashboard")
    public ResponseEntity<ApiResponse<ReceiverDashboardResponse>> getReceiverDashboard(@PathVariable UUID id) {
        try {
            ReceiverDashboardResponse response = receiverService.getReceiverDashboard(id);
            return ResponseEntity.ok(ApiResponse.success("Receiver dashboard retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/submerchants")
    public ResponseEntity<ApiResponse<ReceiverResponse>> createSubmerchant(
            @PathVariable UUID id,
            @Valid @RequestBody CreateReceiverRequest request) {
        try {
            ReceiverResponse response = receiverService.createSubmerchant(id, request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("Submerchant created and linked successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/submerchants/link")
    public ResponseEntity<ApiResponse<ReceiverResponse>> linkExistingReceiverAsSubmerchant(
            @PathVariable UUID id,
            @Valid @RequestBody CreateSubmerchantRequest request) {
        try {
            ReceiverResponse response = receiverService.linkExistingReceiverAsSubmerchant(id, request);
            return ResponseEntity.ok(ApiResponse.success("Existing receiver linked as submerchant successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}/submerchants")
    public ResponseEntity<ApiResponse<List<ReceiverResponse>>> getSubmerchants(@PathVariable UUID id) {
        try {
            List<ReceiverResponse> submerchants = receiverService.getSubmerchants(id);
            return ResponseEntity.ok(ApiResponse.success("Submerchants retrieved successfully", submerchants));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/main-merchants")
    public ResponseEntity<ApiResponse<List<ReceiverResponse>>> getAllMainMerchants() {
        try {
            List<ReceiverResponse> mainMerchants = receiverService.getAllMainMerchants();
            return ResponseEntity.ok(ApiResponse.success("Main merchants retrieved successfully", mainMerchants));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/submerchants/{submerchantId}/unlink")
    public ResponseEntity<ApiResponse<ReceiverResponse>> unlinkSubmerchant(
            @PathVariable UUID id,
            @PathVariable UUID submerchantId) {
        try {
            // Verify the main merchant ID matches (for consistency, though we only need submerchantId)
            ReceiverResponse response = receiverService.unlinkSubmerchant(submerchantId);
            return ResponseEntity.ok(ApiResponse.success("Submerchant unlinked successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/submerchants/{submerchantId}")
    public ResponseEntity<ApiResponse<ReceiverResponse>> updateSubmerchant(
            @PathVariable UUID id,
            @PathVariable UUID submerchantId,
            @Valid @RequestBody UpdateReceiverRequest request) {
        try {
            ReceiverResponse response = receiverService.updateSubmerchant(id, submerchantId, request);
            return ResponseEntity.ok(ApiResponse.success("Submerchant updated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}/submerchants/{submerchantId}/change-parent")
    public ResponseEntity<ApiResponse<ReceiverResponse>> updateSubmerchantParent(
            @PathVariable UUID id,
            @PathVariable UUID submerchantId,
            @Valid @RequestBody CreateSubmerchantRequest request) {
        try {
            ReceiverResponse response = receiverService.updateSubmerchantParent(submerchantId, request);
            return ResponseEntity.ok(ApiResponse.success("Submerchant parent updated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/submerchants/{submerchantId}")
    public ResponseEntity<ApiResponse<Void>> deleteSubmerchant(
            @PathVariable UUID id,
            @PathVariable UUID submerchantId) {
        try {
            receiverService.deleteSubmerchant(id, submerchantId);
            return ResponseEntity.ok(ApiResponse.success("Submerchant deleted successfully", null));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}

