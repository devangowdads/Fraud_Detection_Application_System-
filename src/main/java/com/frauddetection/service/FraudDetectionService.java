package com.frauddetection.service;

import com.frauddetection.dto.FraudResultResponse;
import com.frauddetection.dto.TransactionRequest;
import com.frauddetection.entity.FraudResult;
import com.frauddetection.entity.FraudRule;
import com.frauddetection.entity.Transaction;
import com.frauddetection.enums.TransactionStatus;
import com.frauddetection.repository.FraudResultRepository;
import com.frauddetection.repository.FraudRuleRepository;
import com.frauddetection.repository.TransactionRepository;
import com.frauddetection.startegy.FraudCheckStrategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class FraudDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(FraudDetectionService.class);

    private final TransactionRepository transactionRepository;
    private final FraudRuleRepository fraudRuleRepository;
    private final FraudResultRepository fraudResultRepository;
    private final IdempotencyService idempotencyService;

    // Strategy Pattern: Spring injects all implementations, mapped by rule type
    private final Map<com.frauddetection.enums.RuleType, FraudCheckStrategy> strategyMap;

    public FraudDetectionService(TransactionRepository transactionRepository,
                                  FraudRuleRepository fraudRuleRepository,
                                  FraudResultRepository fraudResultRepository,
                                  IdempotencyService idempotencyService,
                                  List<FraudCheckStrategy> strategies) {
        this.transactionRepository = transactionRepository;
        this.fraudRuleRepository = fraudRuleRepository;
        this.fraudResultRepository = fraudResultRepository;
        this.idempotencyService = idempotencyService;

        this.strategyMap = new HashMap<>();
        for (FraudCheckStrategy strategy : strategies) {
            strategyMap.put(strategy.getRuleType(), strategy);
        }

        logger.info("FraudDetectionService initialized with {} strategies: {}",
                strategyMap.size(), strategyMap.keySet());
    }

    @Transactional
    public FraudResultResponse processTransaction(TransactionRequest request) {

        logger.debug("Processing transaction | idempotencyKey={} accountId={}",
                request.getIdempotencyKey(), request.getAccountId());

        // 1. Idempotency check
        Optional<Transaction> existing = idempotencyService.findExisting(request.getIdempotencyKey());
        if (existing.isPresent()) {
            logger.info("Duplicate idempotencyKey={} detected, returning existing result for transactionId={}",
                    request.getIdempotencyKey(), existing.get().getId());

            FraudResult existingResult = findResultForTransaction(existing.get().getId());
            if (existingResult == null) {
                logger.error("Result missing for existing transactionId={}", existing.get().getId());
                throw new IllegalStateException("Result missing for existing transaction");
            }
            return toResponse(existingResult);
        }

        // 2. Persist transaction
        Transaction transaction = new Transaction();
        transaction.setIdempotencyKey(request.getIdempotencyKey());
        transaction.setAccountId(request.getAccountId());
        transaction.setAmount(request.getAmount());
        transaction.setCountry(request.getCountry());
        transaction.setCity(request.getCity());
        transaction.setSuccess(request.isSuccess());

        try {
            transaction = transactionRepository.saveAndFlush(transaction);
            logger.debug("Transaction persisted | transactionId={}", transaction.getId());
        } catch (DataIntegrityViolationException e) {
            // Handles race condition: two identical requests arriving concurrently
            logger.warn("Race condition detected for idempotencyKey={}, resolving via existing record",
                    request.getIdempotencyKey());

            Optional<Transaction> concurrentOpt = idempotencyService.findExisting(request.getIdempotencyKey());
            if (!concurrentOpt.isPresent()) {
                logger.error("Race condition unresolved for idempotencyKey={}", request.getIdempotencyKey());
                throw e;
            }
            FraudResult existingResult = findResultForTransaction(concurrentOpt.get().getId());
            if (existingResult == null) {
                logger.error("Result missing after race condition for transactionId={}", concurrentOpt.get().getId());
                throw new IllegalStateException("Result missing after race condition");
            }
            return toResponse(existingResult);
        }

        // 3. Run all enabled rules through their strategies
        List<FraudRule> activeRules = fraudRuleRepository.findByEnabledTrue();
        logger.debug("Evaluating {} active fraud rules for transactionId={}", activeRules.size(), transaction.getId());

        List<String> triggeredReasons = new ArrayList<>();

        for (FraudRule rule : activeRules) {
            FraudCheckStrategy strategy = strategyMap.get(rule.getRuleType());
            if (strategy == null) {
                logger.warn("No strategy registered for ruleType={}, skipping", rule.getRuleType());
                continue;
            }
            Optional<String> reason = strategy.evaluate(transaction, rule);
            if (reason.isPresent()) {
                logger.info("Rule triggered | transactionId={} ruleType={} reasonCode={}",
                        transaction.getId(), rule.getRuleType(), reason.get());
                triggeredReasons.add(reason.get());
            }
        }

        // 4. Categorize
        TransactionStatus status = categorize(triggeredReasons);

        // 5. Persist result
        FraudResult result = new FraudResult();
        result.setTransaction(transaction);
        result.setStatus(status);
        StringBuilder reasonCodesBuilder = new StringBuilder();
        for (int i = 0; i < triggeredReasons.size(); i++) {
            if (i > 0) {
                reasonCodesBuilder.append(",");
            }
            reasonCodesBuilder.append(triggeredReasons.get(i));
        }
        result.setReasonCodes(reasonCodesBuilder.toString());
        result = fraudResultRepository.save(result);

        logger.info("Transaction classified | transactionId={} status={} reasonCodes={}",
                transaction.getId(), status, result.getReasonCodes());

        return toResponse(result);
    }

    private FraudResult findResultForTransaction(Long transactionId) {
        List<FraudResult> allResults = fraudResultRepository.findAll();
        for (FraudResult result : allResults) {
            if (result.getTransaction().getId().equals(transactionId)) {
                return result;
            }
        }
        return null;
    }

    private TransactionStatus categorize(List<String> reasons) {
        if (reasons.isEmpty()) {
            return TransactionStatus.SAFE;
        }
        if (reasons.size() == 1) {
            return TransactionStatus.SUSPICIOUS;
        }
        return TransactionStatus.FRAUD; // 2+ rules triggered = high confidence fraud
    }

    private FraudResultResponse toResponse(FraudResult result) {
        return new FraudResultResponse(
                result.getTransaction().getId(),
                result.getStatus(),
                result.getReasonCodes(),
                result.getEvaluatedAt()
        );
    }
}