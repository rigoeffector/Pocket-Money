package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.Transaction;
import com.pocketmoney.pocketmoney.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    List<Transaction> findByUser(User user);
    List<Transaction> findByUserOrderByCreatedAtDesc(User user);
    Optional<Transaction> findByMopayTransactionId(String mopayTransactionId);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.user ORDER BY t.createdAt DESC")
    List<Transaction> findAllWithUser();

    @Query("SELECT t FROM Transaction t JOIN FETCH t.user WHERE t.user = :user ORDER BY t.createdAt DESC")
    List<Transaction> findByUserOrderByCreatedAtDescWithUser(@Param("user") User user);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.user WHERE t.id = :id")
    Optional<Transaction> findByIdWithUser(@Param("id") UUID id);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.user WHERE t.mopayTransactionId = :mopayTransactionId")
    Optional<Transaction> findByMopayTransactionIdWithUser(@Param("mopayTransactionId") String mopayTransactionId);
}

