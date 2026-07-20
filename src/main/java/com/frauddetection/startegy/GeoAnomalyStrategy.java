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
public class GeoAnomalyStrategy implements FraudCheckStrategy {

    private static final Logger logger = LoggerFactory.getLogger(GeoAnomalyStrategy.class);

    private final TransactionRepository transactionRepository;

    @Autowired
    public GeoAnomalyStrategy(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public RuleType getRuleType() {
        return RuleType.GEO_ANOMALY;
    }

    @Override
    public Optional<String> evaluate(Transaction transaction, FraudRule rule) {
        if (rule.getWindowMinutes() == null || transaction.getCountry() == null) {
            return Optional.empty();
        }
        LocalDateTime since = transaction.getCreatedAt().minusMinutes(rule.getWindowMinutes());
        List<Transaction> recent = transactionRepository
                .findRecentByAccount(transaction.getAccountId(), since);

        boolean anomaly = false;
        for (Transaction t : recent) {
            if (t.getCountry() != null && !t.getCountry().equalsIgnoreCase(transaction.getCountry())) {
                anomaly = true;
                break;
            }
        }

        if (anomaly) {
            logger.debug("GEO_ANOMALY triggered | transactionId={} accountId={} country={}",
                    transaction.getId(), transaction.getAccountId(), transaction.getCountry());
            return Optional.of("GEO_ANOMALY_DETECTED");
        }
        return Optional.empty();
    }
}