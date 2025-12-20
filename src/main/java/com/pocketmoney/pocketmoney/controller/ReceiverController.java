package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.CreateReceiverRequest;
import com.pocketmoney.pocketmoney.dto.ReceiverResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverWalletResponse;
import com.pocketmoney.pocketmoney.dto.UpdateReceiverRequest;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import com.pocketmoney.pocketmoney.service.ReceiverService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}

