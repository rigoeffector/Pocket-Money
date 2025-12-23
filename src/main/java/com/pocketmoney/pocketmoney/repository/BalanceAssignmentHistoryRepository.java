package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.BalanceAssignmentHistory;
import com.pocketmoney.pocketmoney.entity.Receiver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BalanceAssignmentHistoryRepository extends JpaRepository<BalanceAssignmentHistory, UUID> {
    List<BalanceAssignmentHistory> findByReceiverOrderByCreatedAtDesc(Receiver receiver);

    @Query("SELECT b FROM BalanceAssignmentHistory b WHERE b.receiver.id = :receiverId ORDER BY b.createdAt DESC")
    List<BalanceAssignmentHistory> findByReceiverIdOrderByCreatedAtDesc(@Param("receiverId") UUID receiverId);

    @Query("SELECT b FROM BalanceAssignmentHistory b WHERE b.receiver.id = :receiverId AND b.status = 'PENDING' ORDER BY b.createdAt DESC")
    List<BalanceAssignmentHistory> findPendingByReceiverId(@Param("receiverId") UUID receiverId);
}

