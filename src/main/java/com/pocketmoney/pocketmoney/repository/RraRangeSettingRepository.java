package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.RraRangeSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RraRangeSettingRepository extends JpaRepository<RraRangeSetting, UUID> {

    /**
     * Find all active range settings ordered by priority (ascending)
     */
    List<RraRangeSetting> findByIsActiveTrueOrderByPriorityAsc();

    /**
     * Find all range settings (active and inactive) ordered by priority
     */
    List<RraRangeSetting> findAllByOrderByPriorityAsc();

    /**
     * Find the applicable range setting for a given amount
     * Returns the first active range where minAmount <= amount < maxAmount (or maxAmount is null)
     * Ordered by priority (ascending)
     */
    @Query("SELECT r FROM RraRangeSetting r WHERE r.isActive = true " +
           "AND r.minAmount <= :amount " +
           "AND (r.maxAmount IS NULL OR r.maxAmount > :amount) " +
           "ORDER BY r.priority ASC")
    Optional<RraRangeSetting> findApplicableRange(BigDecimal amount);
}
