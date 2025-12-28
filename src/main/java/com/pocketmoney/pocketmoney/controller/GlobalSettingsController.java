package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.GlobalSettingsResponse;
import com.pocketmoney.pocketmoney.dto.UpdateGlobalSettingsRequest;
import com.pocketmoney.pocketmoney.service.GlobalSettingsService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/global-settings")
public class GlobalSettingsController {

    private static final Logger logger = LoggerFactory.getLogger(GlobalSettingsController.class);

    private final GlobalSettingsService globalSettingsService;

    public GlobalSettingsController(GlobalSettingsService globalSettingsService) {
        this.globalSettingsService = globalSettingsService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<GlobalSettingsResponse>> getGlobalSettings() {
        try {
            GlobalSettingsResponse response = globalSettingsService.getGlobalSettings();
            return ResponseEntity.ok(ApiResponse.success("Global settings retrieved successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<ApiResponse<GlobalSettingsResponse>> updateGlobalSettings(
            @Valid @RequestBody UpdateGlobalSettingsRequest request) {
        try {
            GlobalSettingsResponse response = globalSettingsService.updateGlobalSettings(request);
            return ResponseEntity.ok(ApiResponse.success("Global settings updated successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}

