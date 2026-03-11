package com.finn.repository;

import com.finn.model.LinkedAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface LinkedAccountRepository extends JpaRepository<LinkedAccount, Long> {
    List<LinkedAccount> findByUserIdAndActiveTrue(Long userId);
    Optional<LinkedAccount> findByAaConsentId(String aaConsentId);
    long countByUserId(Long userId);
}
