package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.Loan;
import com.pocketmoney.pocketmoney.entity.LoanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LoanRepository extends JpaRepository<Loan, UUID> {
    List<Loan> findByUserId(UUID userId);
    List<Loan> findByReceiverId(UUID receiverId);
    List<Loan> findByUserIdAndReceiverId(UUID userId, UUID receiverId);
    List<Loan> findByStatus(LoanStatus status);
    Optional<Loan> findByTransactionId(UUID transactionId);
    List<Loan> findByUserIdAndStatus(UUID userId, LoanStatus status);
}

