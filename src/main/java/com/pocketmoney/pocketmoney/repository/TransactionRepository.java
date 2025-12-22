package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.PaymentCategory;
import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.entity.Transaction;
import com.pocketmoney.pocketmoney.entity.TransactionStatus;
import com.pocketmoney.pocketmoney.entity.TransactionType;
import com.pocketmoney.pocketmoney.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    List<Transaction> findByUser(User user);
    List<Transaction> findByUserOrderByCreatedAtDesc(User user);
    Optional<Transaction> findByMopayTransactionId(String mopayTransactionId);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.user LEFT JOIN FETCH t.paymentCategory ORDER BY t.createdAt DESC")
    List<Transaction> findAllWithUser();

    @Query("SELECT t FROM Transaction t JOIN FETCH t.user LEFT JOIN FETCH t.paymentCategory WHERE t.user = :user ORDER BY t.createdAt DESC")
    List<Transaction> findByUserOrderByCreatedAtDescWithUser(@Param("user") User user);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.user LEFT JOIN FETCH t.paymentCategory WHERE t.id = :id")
    Optional<Transaction> findByIdWithUser(@Param("id") UUID id);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.user LEFT JOIN FETCH t.paymentCategory WHERE t.mopayTransactionId = :mopayTransactionId")
    Optional<Transaction> findByMopayTransactionIdWithUser(@Param("mopayTransactionId") String mopayTransactionId);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.user LEFT JOIN FETCH t.paymentCategory WHERE t.receiver = :receiver ORDER BY t.createdAt DESC")
    List<Transaction> findByReceiverOrderByCreatedAtDescWithUser(@Param("receiver") Receiver receiver);

    // Analytics queries for receiver
    @Query("SELECT COUNT(DISTINCT t.user) FROM Transaction t WHERE t.receiver.id = :receiverId AND t.transactionType = 'PAYMENT'")
    Long countDistinctUsersByReceiver(@Param("receiverId") UUID receiverId);

    @Query("SELECT COUNT(DISTINCT t.user) FROM Transaction t WHERE t.receiver.id = :receiverId AND t.transactionType = 'PAYMENT' " +
           "AND (:fromDate IS NULL OR t.createdAt >= :fromDate) AND (:toDate IS NULL OR t.createdAt <= :toDate)")
    Long countDistinctUsersByReceiverAndDateRange(@Param("receiverId") UUID receiverId, 
                                                   @Param("fromDate") LocalDateTime fromDate, 
                                                   @Param("toDate") LocalDateTime toDate);

    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.receiver.id = :receiverId AND t.transactionType = 'PAYMENT' AND t.status = 'SUCCESS'")
    java.math.BigDecimal sumSuccessfulAmountByReceiver(@Param("receiverId") UUID receiverId);

    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.receiver.id = :receiverId AND t.transactionType = 'PAYMENT' AND t.status = 'SUCCESS' " +
           "AND (:fromDate IS NULL OR t.createdAt >= :fromDate) AND (:toDate IS NULL OR t.createdAt <= :toDate) " +
           "AND (:categoryId IS NULL OR t.paymentCategory.id = :categoryId)")
    java.math.BigDecimal sumSuccessfulAmountByReceiverAndFilters(@Param("receiverId") UUID receiverId,
                                                                 @Param("fromDate") LocalDateTime fromDate,
                                                                 @Param("toDate") LocalDateTime toDate,
                                                                 @Param("categoryId") UUID categoryId);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.receiver.id = :receiverId AND t.transactionType = 'PAYMENT'")
    Long countAllTransactionsByReceiver(@Param("receiverId") UUID receiverId);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.receiver.id = :receiverId AND t.transactionType = 'PAYMENT' " +
           "AND (:fromDate IS NULL OR t.createdAt >= :fromDate) AND (:toDate IS NULL OR t.createdAt <= :toDate) " +
           "AND (:categoryId IS NULL OR t.paymentCategory.id = :categoryId)")
    Long countAllTransactionsByReceiverAndFilters(@Param("receiverId") UUID receiverId,
                                                   @Param("fromDate") LocalDateTime fromDate,
                                                   @Param("toDate") LocalDateTime toDate,
                                                   @Param("categoryId") UUID categoryId);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.receiver.id = :receiverId AND t.transactionType = 'PAYMENT' AND t.status = 'SUCCESS'")
    Long countSuccessfulTransactionsByReceiver(@Param("receiverId") UUID receiverId);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.receiver.id = :receiverId AND t.transactionType = 'PAYMENT' AND t.status = 'SUCCESS' " +
           "AND (:fromDate IS NULL OR t.createdAt >= :fromDate) AND (:toDate IS NULL OR t.createdAt <= :toDate) " +
           "AND (:categoryId IS NULL OR t.paymentCategory.id = :categoryId)")
    Long countSuccessfulTransactionsByReceiverAndFilters(@Param("receiverId") UUID receiverId,
                                                          @Param("fromDate") LocalDateTime fromDate,
                                                          @Param("toDate") LocalDateTime toDate,
                                                          @Param("categoryId") UUID categoryId);

    @Query("SELECT t.paymentCategory, COUNT(t), SUM(t.amount) FROM Transaction t " +
           "WHERE t.receiver.id = :receiverId AND t.transactionType = 'PAYMENT' AND t.status = 'SUCCESS' " +
           "AND (:fromDate IS NULL OR t.createdAt >= :fromDate) AND (:toDate IS NULL OR t.createdAt <= :toDate) " +
           "AND t.paymentCategory IS NOT NULL " +
           "GROUP BY t.paymentCategory")
    List<Object[]> getCategoryBreakdownByReceiver(@Param("receiverId") UUID receiverId,
                                                   @Param("fromDate") LocalDateTime fromDate,
                                                   @Param("toDate") LocalDateTime toDate);
}

