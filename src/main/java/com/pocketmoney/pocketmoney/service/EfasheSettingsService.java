package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.EfasheSettingsResponse;
import com.pocketmoney.pocketmoney.dto.UpdateEfasheSettingsRequest;
import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import com.pocketmoney.pocketmoney.entity.EfasheSettings;
import com.pocketmoney.pocketmoney.repository.EfasheSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class EfasheSettingsService {

    private static final Logger logger = LoggerFactory.getLogger(EfasheSettingsService.class);

    private final EfasheSettingsRepository efasheSettingsRepository;

    public EfasheSettingsService(EfasheSettingsRepository efasheSettingsRepository) {
        this.efasheSettingsRepository = efasheSettingsRepository;
    }

    public List<EfasheSettingsResponse> getAllSettings() {
        logger.info("Retrieving all EFASHE settings");
        List<EfasheSettings> settings = efasheSettingsRepository.findAll();
        return settings.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public EfasheSettingsResponse getSettingsByServiceType(EfasheServiceType serviceType) {
        logger.info("Retrieving EFASHE settings for service type: {}", serviceType);
        EfasheSettings settings = efasheSettingsRepository.findByServiceType(serviceType)
                .orElseThrow(() -> new RuntimeException("EFASHE settings not found for service type: " + serviceType));
        return mapToResponse(settings);
    }

    @Transactional
    public EfasheSettingsResponse updateSettings(EfasheServiceType serviceType, UpdateEfasheSettingsRequest request) {
        logger.info("Updating or creating EFASHE settings for service type: {}", serviceType);
        
        EfasheSettings settings = efasheSettingsRepository.findByServiceType(serviceType)
                .orElse(new EfasheSettings());

        // If it's a new settings object, set the service type
        if (settings.getServiceType() == null) {
            settings.setServiceType(serviceType);
            logger.info("Creating new EFASHE settings for service type: {}", serviceType);
        } else {
            logger.info("Updating existing EFASHE settings for service type: {}", serviceType);
        }

        settings.setFullAmountPhoneNumber(request.getFullAmountPhoneNumber());
        settings.setCashbackPhoneNumber(request.getCashbackPhoneNumber());
        settings.setAgentCommissionPercentage(request.getAgentCommissionPercentage());
        settings.setCustomerCashbackPercentage(request.getCustomerCashbackPercentage());
        settings.setBesoftSharePercentage(request.getBesoftSharePercentage());

        EfasheSettings savedSettings = efasheSettingsRepository.save(settings);
        logger.info("EFASHE settings saved successfully for service type: {}", serviceType);
        return mapToResponse(savedSettings);
    }

    @Transactional
    public void deleteSettings(EfasheServiceType serviceType) {
        logger.info("Deleting EFASHE settings for service type: {}", serviceType);
        EfasheSettings settings = efasheSettingsRepository.findByServiceType(serviceType)
                .orElseThrow(() -> new RuntimeException("EFASHE settings not found for service type: " + serviceType));
        
        efasheSettingsRepository.delete(settings);
        logger.info("EFASHE settings deleted successfully for service type: {}", serviceType);
    }

    private EfasheSettingsResponse mapToResponse(EfasheSettings settings) {
        EfasheSettingsResponse response = new EfasheSettingsResponse();
        response.setId(settings.getId());
        response.setServiceType(settings.getServiceType());
        response.setFullAmountPhoneNumber(settings.getFullAmountPhoneNumber());
        response.setCashbackPhoneNumber(settings.getCashbackPhoneNumber());
        response.setAgentCommissionPercentage(settings.getAgentCommissionPercentage());
        response.setCustomerCashbackPercentage(settings.getCustomerCashbackPercentage());
        response.setBesoftSharePercentage(settings.getBesoftSharePercentage());
        response.setCreatedAt(settings.getCreatedAt());
        response.setUpdatedAt(settings.getUpdatedAt());
        return response;
    }
}

