package com.frauddetection.repository;

import com.frauddetection.entity.FraudRule;
import com.frauddetection.enums.RuleType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FraudRuleRepository extends JpaRepository<FraudRule, Long> {
    List<FraudRule> findByEnabledTrue();
    Optional<FraudRule> findByRuleType(RuleType ruleType);
}