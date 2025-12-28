package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.GlobalSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GlobalSettingsRepository extends JpaRepository<GlobalSettings, UUID> {
    
    // Get the first (and should be only) global settings record
    Optional<GlobalSettings> findFirstByOrderByCreatedAtAsc();
}

