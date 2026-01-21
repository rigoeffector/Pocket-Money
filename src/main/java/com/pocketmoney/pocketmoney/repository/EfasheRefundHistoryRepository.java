package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.EfasheRefundHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EfasheRefundHistoryRepository extends JpaRepository<EfasheRefundHistory, UUID> {
    
    Optional<EfasheRefundHistory> findByRefundTransactionId(String refundTransactionId);
    
    List<EfasheRefundHistory> findByOriginalTransactionId(String originalTransactionId);
    
    List<EfasheRefundHistory> findByReceiverPhone(String receiverPhone);
    
    List<EfasheRefundHistory> findByStatus(String status);
    
    Page<EfasheRefundHistory> findByReceiverPhoneOrderByCreatedAtDesc(String receiverPhone, Pageable pageable);
    
    Page<EfasheRefundHistory> findByReceiverPhoneAndStatusOrderByCreatedAtDesc(String receiverPhone, String status, Pageable pageable);
    
    Page<EfasheRefundHistory> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    
    Page<EfasheRefundHistory> findAllByOrderByCreatedAtDesc(Pageable pageable);
    
    @Query("SELECT r FROM EfasheRefundHistory r WHERE r.efasheTransaction.id = :transactionId ORDER BY r.createdAt DESC")
    List<EfasheRefundHistory> findByEfasheTransactionId(@Param("transactionId") UUID transactionId);
}
