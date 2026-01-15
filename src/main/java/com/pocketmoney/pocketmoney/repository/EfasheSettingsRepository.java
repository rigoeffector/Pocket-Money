package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import com.pocketmoney.pocketmoney.entity.EfasheSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EfasheSettingsRepository extends JpaRepository<EfasheSettings, UUID> {
    Optional<EfasheSettings> findByServiceType(EfasheServiceType serviceType);
}

