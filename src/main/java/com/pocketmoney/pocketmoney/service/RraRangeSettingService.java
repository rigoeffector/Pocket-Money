package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.CreateRraRangeSettingRequest;
import com.pocketmoney.pocketmoney.dto.RraRangeSettingResponse;
import com.pocketmoney.pocketmoney.dto.UpdateRraRangeSettingRequest;
import com.pocketmoney.pocketmoney.entity.RraRangeSetting;
import com.pocketmoney.pocketmoney.repository.RraRangeSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RraRangeSettingService {

    private static final Logger logger = LoggerFactory.getLogger(RraRangeSettingService.class);

    private final RraRangeSettingRepository rraRangeSettingRepository;

    public RraRangeSettingService(RraRangeSettingRepository rraRangeSettingRepository) {
        this.rraRangeSettingRepository = rraRangeSettingRepository;
    }

    /**
     * Get all RRA range settings ordered by priority
     */
    public List<RraRangeSettingResponse> getAllRangeSettings() {
        logger.info("Retrieving all RRA range settings");
        List<RraRangeSetting> settings = rraRangeSettingRepository.findAllByOrderByPriorityAsc();
        return settings.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get active RRA range settings ordered by priority
     */
    public List<RraRangeSettingResponse> getActiveRangeSettings() {
        logger.info("Retrieving active RRA range settings");
        List<RraRangeSetting> settings = rraRangeSettingRepository.findByIsActiveTrueOrderByPriorityAsc();
        return settings.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific range setting by ID
     */
    public RraRangeSettingResponse getRangeSettingById(UUID id) {
        logger.info("Retrieving RRA range setting with ID: {}", id);
        RraRangeSetting setting = rraRangeSettingRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("RRA range setting not found with ID: " + id));
        return mapToResponse(setting);
    }

    /**
     * Create multiple RRA range settings in bulk
     */
    @Transactional
    public List<RraRangeSettingResponse> createRangeSettingsBulk(List<CreateRraRangeSettingRequest> requests) {
        logger.info("Creating {} RRA range settings in bulk", requests.size());
        
        List<RraRangeSettingResponse> createdSettings = new java.util.ArrayList<>();
        
        for (CreateRraRangeSettingRequest request : requests) {
            try {
                RraRangeSettingResponse setting = createRangeSetting(request);
                createdSettings.add(setting);
            } catch (Exception e) {
                logger.error("Error creating range setting in bulk: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to create range setting: " + e.getMessage() + ". All changes rolled back.");
            }
        }
        
        logger.info("Successfully created {} RRA range settings", createdSettings.size());
        return createdSettings;
    }

    /**
     * Create a new RRA range setting
     */
    @Transactional
    public RraRangeSettingResponse createRangeSetting(CreateRraRangeSettingRequest request) {
        logger.info("Creating new RRA range setting: minAmount={}, maxAmount={}, percentage={}%", 
                request.getMinAmount(), request.getMaxAmount(), request.getPercentage());

        // Validate percentage range
        if (request.getPercentage().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new RuntimeException("Percentage cannot exceed 100%");
        }

        // Validate min/max amounts
        if (request.getMaxAmount() != null && request.getMinAmount().compareTo(request.getMaxAmount()) >= 0) {
            throw new RuntimeException("Min amount must be less than max amount");
        }

        RraRangeSetting setting = new RraRangeSetting();
        setting.setMinAmount(request.getMinAmount());
        setting.setMaxAmount(request.getMaxAmount());
        setting.setPercentage(request.getPercentage());
        setting.setPriority(request.getPriority());
        setting.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
        setting.setDescription(request.getDescription());

        RraRangeSetting savedSetting = rraRangeSettingRepository.save(setting);
        logger.info("RRA range setting created successfully with ID: {}", savedSetting.getId());
        return mapToResponse(savedSetting);
    }

    /**
     * Update an existing RRA range setting
     */
    @Transactional
    public RraRangeSettingResponse updateRangeSetting(UUID id, UpdateRraRangeSettingRequest request) {
        logger.info("Updating RRA range setting with ID: {}", id);
        
        RraRangeSetting setting = rraRangeSettingRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("RRA range setting not found with ID: " + id));

        if (request.getMinAmount() != null) {
            setting.setMinAmount(request.getMinAmount());
        }
        if (request.getMaxAmount() != null) {
            setting.setMaxAmount(request.getMaxAmount());
        }
        if (request.getPercentage() != null) {
            if (request.getPercentage().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new RuntimeException("Percentage cannot exceed 100%");
            }
            setting.setPercentage(request.getPercentage());
        }
        if (request.getPriority() != null) {
            setting.setPriority(request.getPriority());
        }
        if (request.getIsActive() != null) {
            setting.setIsActive(request.getIsActive());
        }
        if (request.getDescription() != null) {
            setting.setDescription(request.getDescription());
        }

        // Validate min/max amounts if both are set
        if (setting.getMaxAmount() != null && setting.getMinAmount().compareTo(setting.getMaxAmount()) >= 0) {
            throw new RuntimeException("Min amount must be less than max amount");
        }

        RraRangeSetting savedSetting = rraRangeSettingRepository.save(setting);
        logger.info("RRA range setting updated successfully with ID: {}", savedSetting.getId());
        return mapToResponse(savedSetting);
    }

    /**
     * Delete a RRA range setting
     */
    @Transactional
    public void deleteRangeSetting(UUID id) {
        logger.info("Deleting RRA range setting with ID: {}", id);
        RraRangeSetting setting = rraRangeSettingRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("RRA range setting not found with ID: " + id));
        
        rraRangeSettingRepository.delete(setting);
        logger.info("RRA range setting deleted successfully with ID: {}", id);
    }

    /**
     * Get the applicable percentage for a given amount
     * Returns the percentage from the first matching active range, or 0 if no range matches
     */
    public BigDecimal getApplicablePercentage(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        Optional<RraRangeSetting> rangeSetting = rraRangeSettingRepository.findApplicableRange(amount);
        
        if (rangeSetting.isPresent()) {
            BigDecimal percentage = rangeSetting.get().getPercentage();
            logger.debug("Found applicable RRA range for amount {}: percentage={}%", amount, percentage);
            return percentage;
        }

        logger.debug("No applicable RRA range found for amount {}, returning 0%", amount);
        return BigDecimal.ZERO;
    }

    private RraRangeSettingResponse mapToResponse(RraRangeSetting setting) {
        RraRangeSettingResponse response = new RraRangeSettingResponse();
        response.setId(setting.getId());
        response.setMinAmount(setting.getMinAmount());
        response.setMaxAmount(setting.getMaxAmount());
        response.setPercentage(setting.getPercentage());
        response.setIsActive(setting.getIsActive());
        response.setPriority(setting.getPriority());
        response.setDescription(setting.getDescription());
        response.setCreatedAt(setting.getCreatedAt());
        response.setUpdatedAt(setting.getUpdatedAt());
        return response;
    }
}
