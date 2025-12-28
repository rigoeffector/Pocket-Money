package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.PaymentCommissionSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentCommissionSettingRepository extends JpaRepository<PaymentCommissionSetting, UUID> {
    
    List<PaymentCommissionSetting> findByReceiverId(UUID receiverId);
    
    List<PaymentCommissionSetting> findByReceiverIdAndIsActiveTrue(UUID receiverId);
    
    Optional<PaymentCommissionSetting> findByReceiverIdAndPhoneNumber(UUID receiverId, String phoneNumber);
    
    boolean existsByReceiverIdAndPhoneNumber(UUID receiverId, String phoneNumber);
}

