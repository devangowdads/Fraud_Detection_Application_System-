package com.frauddetection.startegy;
import com.frauddetection.entity.FraudRule;
import com.frauddetection.entity.Transaction;
import com.frauddetection.enums.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class HighValueStrategy implements FraudCheckStrategy {

    private static final Logger logger = LoggerFactory.getLogger(HighValueStrategy.class);

    @Override
    public RuleType getRuleType() {
        return RuleType.HIGH_VALUE;
    }

    @Override
    public Optional<String> evaluate(Transaction transaction, FraudRule rule) {
        if (rule.getThresholdAmount() != null &&
                transaction.getAmount().compareTo(rule.getThresholdAmount()) > 0) {
            logger.debug("HIGH_VALUE triggered | transactionId={} amount={} threshold={}",
                    transaction.getId(), transaction.getAmount(), rule.getThresholdAmount());
            return Optional.of("HIGH_VALUE_TXN");
        }
        return Optional.empty();
    }
}