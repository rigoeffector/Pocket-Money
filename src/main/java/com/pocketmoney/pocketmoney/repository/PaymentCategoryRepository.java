package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.PaymentCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentCategoryRepository extends JpaRepository<PaymentCategory, UUID> {
    Optional<PaymentCategory> findByName(String name);
    boolean existsByName(String name);
    List<PaymentCategory> findByIsActiveTrue();
}

