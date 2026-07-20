package com.frauddetection.repository;

import com.frauddetection.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId " +
           "AND t.createdAt >= :since ORDER BY t.createdAt DESC")
    List<Transaction> findRecentByAccount(@Param("accountId") String accountId,
                                           @Param("since") LocalDateTime since);

    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId " +
           "AND t.success = false AND t.createdAt >= :since ORDER BY t.createdAt DESC")
    List<Transaction> findRecentFailuresByAccount(@Param("accountId") String accountId,
                                                    @Param("since") LocalDateTime since);
}