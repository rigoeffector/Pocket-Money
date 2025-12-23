package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.entity.Transaction;
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

    @Query(value = "SELECT COUNT(DISTINCT t.user_id) FROM transactions t WHERE t.receiver_id = :receiverId " +
           "AND t.transaction_type = 'PAYMENT' " +
           "AND (:fromDate IS NULL OR t.created_at >= CAST(:fromDate AS timestamp)) " +
           "AND (:toDate IS NULL OR t.created_at <= CAST(:toDate AS timestamp))", nativeQuery = true)
    Long countDistinctUsersByReceiverAndDateRange(@Param("receiverId") UUID receiverId, 
                                                   @Param("fromDate") LocalDateTime fromDate, 
                                                   @Param("toDate") LocalDateTime toDate);

    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.receiver.id = :receiverId AND t.transactionType = 'PAYMENT' AND t.status = 'SUCCESS'")
    java.math.BigDecimal sumSuccessfulAmountByReceiver(@Param("receiverId") UUID receiverId);

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.receiver_id = :receiverId " +
           "AND t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS' " +
           "AND (:fromDate IS NULL OR t.created_at >= CAST(:fromDate AS timestamp)) " +
           "AND (:toDate IS NULL OR t.created_at <= CAST(:toDate AS timestamp)) " +
           "AND (:categoryId IS NULL OR t.payment_category_id = CAST(:categoryId AS uuid))", nativeQuery = true)
    java.math.BigDecimal sumSuccessfulAmountByReceiverAndFilters(@Param("receiverId") UUID receiverId,
                                                                 @Param("fromDate") LocalDateTime fromDate,
                                                                 @Param("toDate") LocalDateTime toDate,
                                                                 @Param("categoryId") UUID categoryId);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.receiver.id = :receiverId AND t.transactionType = 'PAYMENT'")
    Long countAllTransactionsByReceiver(@Param("receiverId") UUID receiverId);

    @Query(value = "SELECT COUNT(*) FROM transactions t WHERE t.receiver_id = :receiverId " +
           "AND t.transaction_type = 'PAYMENT' " +
           "AND (:fromDate IS NULL OR t.created_at >= CAST(:fromDate AS timestamp)) " +
           "AND (:toDate IS NULL OR t.created_at <= CAST(:toDate AS timestamp)) " +
           "AND (:categoryId IS NULL OR t.payment_category_id = CAST(:categoryId AS uuid))", nativeQuery = true)
    Long countAllTransactionsByReceiverAndFilters(@Param("receiverId") UUID receiverId,
                                                   @Param("fromDate") LocalDateTime fromDate,
                                                   @Param("toDate") LocalDateTime toDate,
                                                   @Param("categoryId") UUID categoryId);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.receiver.id = :receiverId AND t.transactionType = 'PAYMENT' AND t.status = 'SUCCESS'")
    Long countSuccessfulTransactionsByReceiver(@Param("receiverId") UUID receiverId);

    @Query(value = "SELECT COUNT(*) FROM transactions t WHERE t.receiver_id = :receiverId " +
           "AND t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS' " +
           "AND (:fromDate IS NULL OR t.created_at >= CAST(:fromDate AS timestamp)) " +
           "AND (:toDate IS NULL OR t.created_at <= CAST(:toDate AS timestamp)) " +
           "AND (:categoryId IS NULL OR t.payment_category_id = CAST(:categoryId AS uuid))", nativeQuery = true)
    Long countSuccessfulTransactionsByReceiverAndFilters(@Param("receiverId") UUID receiverId,
                                                          @Param("fromDate") LocalDateTime fromDate,
                                                          @Param("toDate") LocalDateTime toDate,
                                                          @Param("categoryId") UUID categoryId);

    @Query(value = "SELECT pc.id, pc.name, COUNT(t.id), COALESCE(SUM(t.amount), 0) FROM transactions t " +
           "LEFT JOIN payment_categories pc ON t.payment_category_id = pc.id " +
           "WHERE t.receiver_id = :receiverId AND t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS' " +
           "AND (:fromDate IS NULL OR t.created_at >= CAST(:fromDate AS timestamp)) " +
           "AND (:toDate IS NULL OR t.created_at <= CAST(:toDate AS timestamp)) " +
           "AND t.payment_category_id IS NOT NULL " +
           "GROUP BY pc.id, pc.name", nativeQuery = true)
    List<Object[]> getCategoryBreakdownByReceiver(@Param("receiverId") UUID receiverId,
                                                   @Param("fromDate") LocalDateTime fromDate,
                                                   @Param("toDate") LocalDateTime toDate);

    // Calculate total bonus received by user
    @Query(value = "SELECT COALESCE(SUM(t.user_bonus_amount), 0) FROM transactions t " +
           "WHERE t.user_id = :userId AND t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS' " +
           "AND t.user_bonus_amount IS NOT NULL", nativeQuery = true)
    java.math.BigDecimal sumUserBonusByUserId(@Param("userId") UUID userId);

    // Calculate total admin income - no filters
    @Query(value = "SELECT COALESCE(SUM(t.admin_income_amount), 0) FROM transactions t " +
           "WHERE t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS' " +
           "AND t.admin_income_amount IS NOT NULL", nativeQuery = true)
    java.math.BigDecimal sumAdminIncomeAll();

    // Count transactions with admin income - no filters
    @Query(value = "SELECT COUNT(*) FROM transactions t " +
           "WHERE t.transaction_type = 'PAYMENT' AND t.status = 'SUCCESS' " +
           "AND t.admin_income_amount IS NOT NULL", nativeQuery = true)
    Long countAdminIncomeAll();

}

