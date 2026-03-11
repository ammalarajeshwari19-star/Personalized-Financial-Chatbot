package com.finn.repository;

import com.finn.model.BankSyncLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BankSyncLogRepository extends JpaRepository<BankSyncLog, Long> {
    List<BankSyncLog> findByLinkedAccountIdOrderByStartedAtDesc(Long linkedAccountId);
}
