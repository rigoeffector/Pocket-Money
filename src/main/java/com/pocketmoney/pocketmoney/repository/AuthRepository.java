package com.pocketmoney.pocketmoney.repository;

import com.pocketmoney.pocketmoney.entity.Auth;
import com.pocketmoney.pocketmoney.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthRepository extends JpaRepository<Auth, UUID> {
    Optional<Auth> findByUsername(String username);
    Optional<Auth> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    List<Auth> findByRole(Role role);
}

