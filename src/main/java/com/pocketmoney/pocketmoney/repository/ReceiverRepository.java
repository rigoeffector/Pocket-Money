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
    List<Receiver> findByStatus(ReceiverStatus status);
    List<Receiver> findByStatusIn(List<ReceiverStatus> statuses);
}

