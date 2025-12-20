package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.Transaction;
import com.pocketmoney.pocketmoney.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    List<Transaction> findByUser(User user);
    List<Transaction> findByUserOrderByCreatedAtDesc(User user);
    Optional<Transaction> findByMopayTransactionId(String mopayTransactionId);
}

