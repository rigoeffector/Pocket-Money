package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.EfasheTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EfasheTransactionRepository extends JpaRepository<EfasheTransaction, UUID> {
    Optional<EfasheTransaction> findByTransactionId(String transactionId);
    Optional<EfasheTransaction> findByMopayTransactionId(String mopayTransactionId);
}

