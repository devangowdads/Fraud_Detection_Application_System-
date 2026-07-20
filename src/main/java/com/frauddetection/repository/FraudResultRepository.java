package com.frauddetection.repository;

import com.frauddetection.entity.FraudResult;
import com.frauddetection.enums.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudResultRepository extends JpaRepository<FraudResult, Long> {
    List<FraudResult> findByStatus(TransactionStatus status);
}