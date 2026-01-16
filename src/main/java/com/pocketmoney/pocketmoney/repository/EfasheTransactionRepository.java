package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import com.pocketmoney.pocketmoney.entity.EfasheTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EfasheTransactionRepository extends JpaRepository<EfasheTransaction, UUID> {
    Optional<EfasheTransaction> findByTransactionId(String transactionId);
    Optional<EfasheTransaction> findByMopayTransactionId(String mopayTransactionId);
    
    // Find by service type
    Page<EfasheTransaction> findByServiceType(EfasheServiceType serviceType, Pageable pageable);
    
    // Find by service type with date range
    @Query("SELECT t FROM EfasheTransaction t WHERE t.serviceType = :serviceType " +
           "AND (:fromDate IS NULL OR t.createdAt >= :fromDate) " +
           "AND (:toDate IS NULL OR t.createdAt <= :toDate) " +
           "ORDER BY t.createdAt DESC")
    Page<EfasheTransaction> findByServiceTypeAndDateRange(
        @Param("serviceType") EfasheServiceType serviceType,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );
    
    // Find all with optional service type filter, phone number, and date range
    @Query("SELECT t FROM EfasheTransaction t WHERE " +
           "(:serviceType IS NULL OR t.serviceType = :serviceType) " +
           "AND (:customerPhone IS NULL OR t.customerPhone = :customerPhone) " +
           "AND (:fromDate IS NULL OR t.createdAt >= :fromDate) " +
           "AND (:toDate IS NULL OR t.createdAt <= :toDate) " +
           "ORDER BY t.createdAt DESC")
    Page<EfasheTransaction> findAllWithFilters(
        @Param("serviceType") EfasheServiceType serviceType,
        @Param("customerPhone") String customerPhone,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );
}

