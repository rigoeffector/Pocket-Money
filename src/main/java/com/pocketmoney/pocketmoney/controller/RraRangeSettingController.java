package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.BulkCreateRraRangeSettingRequest;
import com.pocketmoney.pocketmoney.dto.CreateRraRangeSettingRequest;
import com.pocketmoney.pocketmoney.dto.RraRangeSettingResponse;
import com.pocketmoney.pocketmoney.dto.UpdateRraRangeSettingRequest;
import com.pocketmoney.pocketmoney.service.RraRangeSettingService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rra/range-settings")
public class RraRangeSettingController {

    private static final Logger logger = LoggerFactory.getLogger(RraRangeSettingController.class);

    private final RraRangeSettingService rraRangeSettingService;

    public RraRangeSettingController(RraRangeSettingService rraRangeSettingService) {
        this.rraRangeSettingService = rraRangeSettingService;
    }

    /**
     * Get all RRA range settings (Admin only)
     * GET /api/rra/range-settings
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<RraRangeSettingResponse>>> getAllRangeSettings() {
        try {
            List<RraRangeSettingResponse> settings = rraRangeSettingService.getAllRangeSettings();
            return ResponseEntity.ok(ApiResponse.success("RRA range settings retrieved successfully", settings));
        } catch (Exception e) {
            logger.error("Error retrieving RRA range settings: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Get active RRA range settings (Admin only)
     * GET /api/rra/range-settings/active
     */
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<RraRangeSettingResponse>>> getActiveRangeSettings() {
        try {
            List<RraRangeSettingResponse> settings = rraRangeSettingService.getActiveRangeSettings();
            return ResponseEntity.ok(ApiResponse.success("Active RRA range settings retrieved successfully", settings));
        } catch (Exception e) {
            logger.error("Error retrieving active RRA range settings: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Get a specific RRA range setting by ID (Admin only)
     * GET /api/rra/range-settings/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RraRangeSettingResponse>> getRangeSettingById(@PathVariable UUID id) {
        try {
            RraRangeSettingResponse setting = rraRangeSettingService.getRangeSettingById(id);
            return ResponseEntity.ok(ApiResponse.success("RRA range setting retrieved successfully", setting));
        } catch (RuntimeException e) {
            logger.error("Error retrieving RRA range setting with ID {}: ", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Create multiple RRA range settings in bulk (Admin only)
     * POST /api/rra/range-settings/bulk
     */
    @PostMapping("/bulk")
    public ResponseEntity<ApiResponse<List<RraRangeSettingResponse>>> createRangeSettingsBulk(
            @Valid @RequestBody BulkCreateRraRangeSettingRequest request) {
        try {
            List<RraRangeSettingResponse> settings = rraRangeSettingService.createRangeSettingsBulk(request.getRanges());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("RRA range settings created successfully", settings));
        } catch (RuntimeException e) {
            logger.error("Error creating RRA range settings in bulk: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Create a new RRA range setting (Admin only)
     * POST /api/rra/range-settings
     */
    @PostMapping
    public ResponseEntity<ApiResponse<RraRangeSettingResponse>> createRangeSetting(
            @Valid @RequestBody CreateRraRangeSettingRequest request) {
        try {
            RraRangeSettingResponse setting = rraRangeSettingService.createRangeSetting(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("RRA range setting created successfully", setting));
        } catch (RuntimeException e) {
            logger.error("Error creating RRA range setting: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Update an existing RRA range setting (Admin only)
     * PUT /api/rra/range-settings/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RraRangeSettingResponse>> updateRangeSetting(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRraRangeSettingRequest request) {
        try {
            RraRangeSettingResponse setting = rraRangeSettingService.updateRangeSetting(id, request);
            return ResponseEntity.ok(ApiResponse.success("RRA range setting updated successfully", setting));
        } catch (RuntimeException e) {
            logger.error("Error updating RRA range setting with ID {}: ", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Delete a RRA range setting (Admin only)
     * DELETE /api/rra/range-settings/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRangeSetting(@PathVariable UUID id) {
        try {
            rraRangeSettingService.deleteRangeSetting(id);
            return ResponseEntity.ok(ApiResponse.success("RRA range setting deleted successfully", null));
        } catch (RuntimeException e) {
            logger.error("Error deleting RRA range setting with ID {}: ", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}
