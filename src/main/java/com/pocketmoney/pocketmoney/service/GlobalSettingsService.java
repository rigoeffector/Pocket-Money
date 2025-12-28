package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.GlobalSettingsResponse;
import com.pocketmoney.pocketmoney.dto.UpdateGlobalSettingsRequest;
import com.pocketmoney.pocketmoney.entity.GlobalSettings;
import com.pocketmoney.pocketmoney.repository.GlobalSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@Transactional
public class GlobalSettingsService {

    private static final Logger logger = LoggerFactory.getLogger(GlobalSettingsService.class);

    private final GlobalSettingsRepository globalSettingsRepository;

    public GlobalSettingsService(GlobalSettingsRepository globalSettingsRepository) {
        this.globalSettingsRepository = globalSettingsRepository;
    }

    @Transactional(readOnly = true)
    public GlobalSettingsResponse getGlobalSettings() {
        GlobalSettings settings = getOrCreateGlobalSettings();
        return mapToResponse(settings);
    }

    public GlobalSettingsResponse updateGlobalSettings(UpdateGlobalSettingsRequest request) {
        GlobalSettings settings = getOrCreateGlobalSettings();

        if (request.getAdminDiscountPercentage() != null) {
            settings.setAdminDiscountPercentage(request.getAdminDiscountPercentage());
        }

        if (request.getUserBonusPercentage() != null) {
            settings.setUserBonusPercentage(request.getUserBonusPercentage());
        }

        GlobalSettings updatedSettings = globalSettingsRepository.save(settings);
        logger.info("Updated global settings - Admin discount: {}%, User bonus: {}%", 
                updatedSettings.getAdminDiscountPercentage(), updatedSettings.getUserBonusPercentage());
        
        return mapToResponse(updatedSettings);
    }

    /**
     * Get global settings or create default if none exists
     */
    private GlobalSettings getOrCreateGlobalSettings() {
        Optional<GlobalSettings> existing = globalSettingsRepository.findFirstByOrderByCreatedAtAsc();
        if (existing.isPresent()) {
            return existing.get();
        }
        
        // Create default global settings
        GlobalSettings defaultSettings = new GlobalSettings();
        defaultSettings.setAdminDiscountPercentage(BigDecimal.ZERO);
        defaultSettings.setUserBonusPercentage(BigDecimal.ZERO);
        return globalSettingsRepository.save(defaultSettings);
    }

    /**
     * Get the current global settings (used by payment service)
     */
    @Transactional(readOnly = true)
    public GlobalSettings getCurrentGlobalSettings() {
        return getOrCreateGlobalSettings();
    }

    private GlobalSettingsResponse mapToResponse(GlobalSettings settings) {
        GlobalSettingsResponse response = new GlobalSettingsResponse();
        response.setId(settings.getId());
        response.setAdminDiscountPercentage(settings.getAdminDiscountPercentage());
        response.setUserBonusPercentage(settings.getUserBonusPercentage());
        response.setCreatedAt(settings.getCreatedAt());
        response.setUpdatedAt(settings.getUpdatedAt());
        return response;
    }
}

