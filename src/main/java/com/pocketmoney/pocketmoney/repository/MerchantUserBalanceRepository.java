package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.MerchantUserBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantUserBalanceRepository extends JpaRepository<MerchantUserBalance, UUID> {
    Optional<MerchantUserBalance> findByUserIdAndReceiverId(UUID userId, UUID receiverId);
    java.util.List<MerchantUserBalance> findByUserId(UUID userId);
    java.util.List<MerchantUserBalance> findByReceiverId(UUID receiverId);
}

