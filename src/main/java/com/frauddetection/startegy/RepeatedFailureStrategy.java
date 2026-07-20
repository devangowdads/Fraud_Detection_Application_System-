package com.frauddetection.startegy;
import com.frauddetection.entity.FraudRule;
import com.frauddetection.entity.Transaction;
import com.frauddetection.enums.RuleType;
import com.frauddetection.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class RepeatedFailureStrategy implements FraudCheckStrategy {

    private static final Logger logger = LoggerFactory.getLogger(RepeatedFailureStrategy.class);

    private final TransactionRepository transactionRepository;

    @Autowired
    public RepeatedFailureStrategy(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public RuleType getRuleType() {
        return RuleType.REPEATED_FAILURE;
    }

    @Override
    public Optional<String> evaluate(Transaction transaction, FraudRule rule) {
        if (rule.getThresholdCount() == null || rule.getWindowMinutes() == null) {
            return Optional.empty();
        }
        LocalDateTime since = transaction.getCreatedAt().minusMinutes(rule.getWindowMinutes());
        List<Transaction> failures = transactionRepository
                .findRecentFailuresByAccount(transaction.getAccountId(), since);

        logger.debug("REPEATED_FAILURE check | accountId={} failureCount={} threshold={}",
                transaction.getAccountId(), failures.size(), rule.getThresholdCount());

        if (failures.size() >= rule.getThresholdCount()) {
            logger.debug("REPEATED_FAILURE triggered | transactionId={} accountId={}",
                    transaction.getId(), transaction.getAccountId());
            return Optional.of("REPEATED_FAILURES");
        }
        return Optional.empty();
    }
}