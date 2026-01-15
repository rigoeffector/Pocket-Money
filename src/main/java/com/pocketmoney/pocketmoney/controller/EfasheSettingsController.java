package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.EfasheSettingsResponse;
import com.pocketmoney.pocketmoney.dto.UpdateEfasheSettingsRequest;
import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import com.pocketmoney.pocketmoney.service.EfasheSettingsService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/efashe/settings")
public class EfasheSettingsController {

    private static final Logger logger = LoggerFactory.getLogger(EfasheSettingsController.class);

    private final EfasheSettingsService efasheSettingsService;

    public EfasheSettingsController(EfasheSettingsService efasheSettingsService) {
        this.efasheSettingsService = efasheSettingsService;
    }

    /**
     * Get all EFASHE settings (Admin only)
     * GET /api/efashe/settings
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<EfasheSettingsResponse>>> getAllSettings() {
        try {
            List<EfasheSettingsResponse> settings = efasheSettingsService.getAllSettings();
            return ResponseEntity.ok(ApiResponse.success("EFASHE settings retrieved successfully", settings));
        } catch (RuntimeException e) {
            logger.error("Error retrieving EFASHE settings: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Get EFASHE settings for a specific service type (Admin only)
     * GET /api/efashe/settings/{serviceType}
     */
    @GetMapping("/{serviceType}")
    public ResponseEntity<ApiResponse<EfasheSettingsResponse>> getSettingsByServiceType(
            @PathVariable EfasheServiceType serviceType) {
        try {
            EfasheSettingsResponse settings = efasheSettingsService.getSettingsByServiceType(serviceType);
            return ResponseEntity.ok(ApiResponse.success("EFASHE settings retrieved successfully", settings));
        } catch (RuntimeException e) {
            logger.error("Error retrieving EFASHE settings for service type {}: ", serviceType, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Update EFASHE settings for a specific service type (Admin only)
     * PUT /api/efashe/settings/{serviceType}
     */
    @PutMapping("/{serviceType}")
    public ResponseEntity<ApiResponse<EfasheSettingsResponse>> updateSettings(
            @PathVariable EfasheServiceType serviceType,
            @Valid @RequestBody UpdateEfasheSettingsRequest request) {
        try {
            EfasheSettingsResponse updatedSettings = efasheSettingsService.updateSettings(serviceType, request);
            return ResponseEntity.ok(ApiResponse.success("EFASHE settings updated successfully", updatedSettings));
        } catch (RuntimeException e) {
            logger.error("Error updating EFASHE settings for service type {}: ", serviceType, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Delete EFASHE settings for a specific service type (Admin only)
     * DELETE /api/efashe/settings/{serviceType}
     */
    @DeleteMapping("/{serviceType}")
    public ResponseEntity<ApiResponse<Void>> deleteSettings(
            @PathVariable EfasheServiceType serviceType) {
        try {
            efasheSettingsService.deleteSettings(serviceType);
            return ResponseEntity.ok(ApiResponse.success("EFASHE settings deleted successfully", null));
        } catch (RuntimeException e) {
            logger.error("Error deleting EFASHE settings for service type {}: ", serviceType, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}

