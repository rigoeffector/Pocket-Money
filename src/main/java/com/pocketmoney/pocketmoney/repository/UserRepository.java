package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByPhoneNumber(String phoneNumber);
    Optional<User> findByNfcCardId(String nfcCardId);
    boolean existsByPhoneNumber(String phoneNumber);
    boolean existsByNfcCardId(String nfcCardId);
    boolean existsByEmail(String email);
}

