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
public class FrequencyStrategy implements FraudCheckStrategy {

    private static final Logger logger = LoggerFactory.getLogger(FrequencyStrategy.class);

    private final TransactionRepository transactionRepository;

    @Autowired
    public FrequencyStrategy(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public RuleType getRuleType() {
        return RuleType.FREQUENCY;
    }

    @Override
    public Optional<String> evaluate(Transaction transaction, FraudRule rule) {
        if (rule.getThresholdCount() == null || rule.getWindowMinutes() == null) {
            return Optional.empty();
        }
        LocalDateTime since = transaction.getCreatedAt().minusMinutes(rule.getWindowMinutes());
        List<Transaction> recent = transactionRepository
                .findRecentByAccount(transaction.getAccountId(), since);

        logger.debug("FREQUENCY check | accountId={} recentCount={} threshold={} windowMinutes={}",
                transaction.getAccountId(), recent.size(), rule.getThresholdCount(), rule.getWindowMinutes());

        if (recent.size() >= rule.getThresholdCount()) {
            logger.debug("FREQUENCY triggered | transactionId={} accountId={}",
                    transaction.getId(), transaction.getAccountId());
            return Optional.of("VELOCITY_BREACH");
        }
        return Optional.empty();
    }
}