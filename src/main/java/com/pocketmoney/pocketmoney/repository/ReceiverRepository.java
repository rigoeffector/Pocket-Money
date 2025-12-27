package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReceiverRepository extends JpaRepository<Receiver, UUID> {
    Optional<Receiver> findByUsername(String username);
    Optional<Receiver> findByReceiverPhone(String receiverPhone);
    boolean existsByUsername(String username);
    boolean existsByReceiverPhone(String receiverPhone);
    boolean existsByEmail(String email);
    List<Receiver> findByStatus(ReceiverStatus status);
    List<Receiver> findByStatusIn(List<ReceiverStatus> statuses);
    
    // Submerchant relationships
    List<Receiver> findByParentReceiverId(UUID parentReceiverId);
    Optional<Receiver> findByIdAndParentReceiverIsNull(UUID id); // Check if main merchant (no parent)
    List<Receiver> findByParentReceiverIsNull(); // Get all main merchants (receivers without parent)
    long countByParentReceiverId(UUID parentReceiverId); // Count submerchants for a main merchant
}

