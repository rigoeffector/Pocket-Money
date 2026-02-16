package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.FailedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FailedMessageRepository extends JpaRepository<FailedMessage, UUID> {
    List<FailedMessage> findByStatusOrderByCreatedAtDesc(String status);
    List<FailedMessage> findByMessageTypeOrderByCreatedAtDesc(String messageType);
    List<FailedMessage> findByMessageTypeAndStatusOrderByCreatedAtDesc(String messageType, String status);
}
