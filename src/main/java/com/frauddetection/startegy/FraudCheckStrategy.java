package com.frauddetection.startegy;

import com.frauddetection.entity.FraudRule;
import com.frauddetection.entity.Transaction;
import com.frauddetection.enums.RuleType;

import java.util.Optional;

public interface FraudCheckStrategy {

    /**
     * Which rule type this strategy handles.
     */
    RuleType getRuleType();

    /**
     * Evaluate the transaction against this rule.
     * Returns a reason code if the rule is triggered, empty otherwise.
     */
    Optional<String> evaluate(Transaction transaction, FraudRule rule);
}