package com.finn.repository;

import com.finn.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByUserIdOrderByTxnDateDesc(Long userId);
    boolean existsByUserIdAndTxnRefId(Long userId, String txnRefId);
}
