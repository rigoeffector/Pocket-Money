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
import java.util.List;
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

    /**
     * Pending transactions (no SUCCESS/FAILED yet) that have been through process and have
     * the external transaction id (same as data.transactionId from POST /api/efashe/process/{id}),
     * created before given time (e.g. 5 mins ago). That id is sent to the bulk status API.
     */
    @Query("SELECT t FROM EfasheTransaction t WHERE " +
           "(t.mopayTransactionId IS NOT NULL AND t.mopayTransactionId != '') " +
           "AND (t.efasheStatus IS NULL OR (t.efasheStatus != 'SUCCESS' AND t.efasheStatus != 'FAILED')) " +
           "AND t.createdAt < :beforeTime " +
           "ORDER BY t.createdAt ASC")
    List<EfasheTransaction> findPendingWithExternalIdCreatedBefore(@Param("beforeTime") LocalDateTime beforeTime);
}

