package com.frauddetection.service;

import com.frauddetection.dto.FraudResultResponse;
import com.frauddetection.dto.TransactionRequest;
import com.frauddetection.entity.FraudResult;
import com.frauddetection.entity.FraudRule;
import com.frauddetection.entity.Transaction;
import com.frauddetection.enums.RuleType;
import com.frauddetection.enums.TransactionStatus;
import com.frauddetection.repository.FraudResultRepository;
import com.frauddetection.repository.FraudRuleRepository;
import com.frauddetection.repository.TransactionRepository;
import com.frauddetection.strategy.FraudCheckStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FraudDetectionService.
 *
 * These tests mock all repositories and strategies so the service's
 * orchestration logic (idempotency, rule evaluation, categorization)
 * can be verified in isolation, without needing a real database.
 */
@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private FraudRuleRepository fraudRuleRepository;

    @Mock
    private FraudResultRepository fraudResultRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private FraudCheckStrategy highValueStrategy;

    @Mock
    private FraudCheckStrategy frequencyStrategy;

    private FraudDetectionService fraudDetectionService;

    @BeforeEach
    void setUp() {
        when(highValueStrategy.getRuleType()).thenReturn(RuleType.HIGH_VALUE);
        when(frequencyStrategy.getRuleType()).thenReturn(RuleType.FREQUENCY);

        List<FraudCheckStrategy> strategies = Arrays.asList(highValueStrategy, frequencyStrategy);

        fraudDetectionService = new FraudDetectionService(
                transactionRepository,
                fraudRuleRepository,
                fraudResultRepository,
                idempotencyService,
                strategies
        );
    }

    private TransactionRequest buildRequest(String idempotencyKey, BigDecimal amount) {
        TransactionRequest request = new TransactionRequest();
        request.setIdempotencyKey(idempotencyKey);
        request.setAccountId("acc-1");
        request.setAmount(amount);
        request.setCountry("US");
        request.setCity("New York");
        request.setSuccess(true);
        return request;
    }

    private FraudRule buildRule(RuleType type) {
        FraudRule rule = new FraudRule();
        rule.setId(1L);
        rule.setRuleType(type);
        rule.setEnabled(true);
        rule.setThresholdAmount(new BigDecimal("10000.00"));
        return rule;
    }

    // ------------------------------------------------------------------
    // Scenario 1: SAFE transaction - no rules triggered
    // ------------------------------------------------------------------
    @Test
    void processTransaction_noRulesTriggered_returnsSafe() {
        TransactionRequest request = buildRequest("txn-safe-1", new BigDecimal("50.00"));

        when(idempotencyService.findExisting("txn-safe-1")).thenReturn(Optional.empty());

        Transaction savedTransaction = new Transaction();
        savedTransaction.setId(1L);
        savedTransaction.setAccountId("acc-1");
        savedTransaction.setAmount(new BigDecimal("50.00"));
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenReturn(savedTransaction);

        FraudRule rule = buildRule(RuleType.HIGH_VALUE);
        when(fraudRuleRepository.findByEnabledTrue()).thenReturn(List.of(rule));
        when(highValueStrategy.evaluate(eq(savedTransaction), eq(rule))).thenReturn(Optional.empty());

        when(fraudResultRepository.save(any(FraudResult.class))).thenAnswer(inv -> {
            FraudResult r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });

        FraudResultResponse response = fraudDetectionService.processTransaction(request);

        assertEquals(TransactionStatus.SAFE, response.getStatus());
        assertEquals("", response.getReasonCodes());
        verify(fraudResultRepository, times(1)).save(any(FraudResult.class));
    }

    // ------------------------------------------------------------------
    // Scenario 2: SUSPICIOUS - exactly one rule triggered (high value)
    // ------------------------------------------------------------------
    @Test
    void processTransaction_oneRuleTriggered_returnsSuspicious() {
        TransactionRequest request = buildRequest("txn-susp-1", new BigDecimal("25000.00"));

        when(idempotencyService.findExisting("txn-susp-1")).thenReturn(Optional.empty());

        Transaction savedTransaction = new Transaction();
        savedTransaction.setId(2L);
        savedTransaction.setAccountId("acc-1");
        savedTransaction.setAmount(new BigDecimal("25000.00"));
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenReturn(savedTransaction);

        FraudRule highValueRule = buildRule(RuleType.HIGH_VALUE);
        when(fraudRuleRepository.findByEnabledTrue()).thenReturn(List.of(highValueRule));
        when(highValueStrategy.evaluate(eq(savedTransaction), eq(highValueRule)))
                .thenReturn(Optional.of("HIGH_VALUE_TXN"));

        when(fraudResultRepository.save(any(FraudResult.class))).thenAnswer(inv -> {
            FraudResult r = inv.getArgument(0);
            r.setId(2L);
            return r;
        });

        FraudResultResponse response = fraudDetectionService.processTransaction(request);

        assertEquals(TransactionStatus.SUSPICIOUS, response.getStatus());
        assertEquals("HIGH_VALUE_TXN", response.getReasonCodes());
    }

    // ------------------------------------------------------------------
    // Scenario 3: FRAUD - two or more rules triggered
    // ------------------------------------------------------------------
    @Test
    void processTransaction_multipleRulesTriggered_returnsFraud() {
        TransactionRequest request = buildRequest("txn-fraud-1", new BigDecimal("50000.00"));

        when(idempotencyService.findExisting("txn-fraud-1")).thenReturn(Optional.empty());

        Transaction savedTransaction = new Transaction();
        savedTransaction.setId(3L);
        savedTransaction.setAccountId("acc-1");
        savedTransaction.setAmount(new BigDecimal("50000.00"));
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenReturn(savedTransaction);

        FraudRule highValueRule = buildRule(RuleType.HIGH_VALUE);
        FraudRule frequencyRule = buildRule(RuleType.FREQUENCY);
        when(fraudRuleRepository.findByEnabledTrue()).thenReturn(Arrays.asList(highValueRule, frequencyRule));

        when(highValueStrategy.evaluate(eq(savedTransaction), eq(highValueRule)))
                .thenReturn(Optional.of("HIGH_VALUE_TXN"));
        when(frequencyStrategy.evaluate(eq(savedTransaction), eq(frequencyRule)))
                .thenReturn(Optional.of("VELOCITY_BREACH"));

        when(fraudResultRepository.save(any(FraudResult.class))).thenAnswer(inv -> {
            FraudResult r = inv.getArgument(0);
            r.setId(3L);
            return r;
        });

        FraudResultResponse response = fraudDetectionService.processTransaction(request);

        assertEquals(TransactionStatus.FRAUD, response.getStatus());
        assertTrue(response.getReasonCodes().contains("HIGH_VALUE_TXN"));
        assertTrue(response.getReasonCodes().contains("VELOCITY_BREACH"));
    }

    // ------------------------------------------------------------------
    // Scenario 4: Idempotency - duplicate key returns existing result
    // without creating a new transaction
    // ------------------------------------------------------------------
    @Test
    void processTransaction_duplicateIdempotencyKey_returnsExistingResultWithoutReprocessing() {
        TransactionRequest request = buildRequest("txn-dup-1", new BigDecimal("50.00"));

        Transaction existingTransaction = new Transaction();
        existingTransaction.setId(4L);
        when(idempotencyService.findExisting("txn-dup-1")).thenReturn(Optional.of(existingTransaction));

        FraudResult existingResult = new FraudResult();
        existingResult.setId(10L);
        existingResult.setTransaction(existingTransaction);
        existingResult.setStatus(TransactionStatus.SAFE);
        existingResult.setReasonCodes("");
        when(fraudResultRepository.findAll()).thenReturn(List.of(existingResult));

        FraudResultResponse response = fraudDetectionService.processTransaction(request);

        assertEquals(TransactionStatus.SAFE, response.getStatus());
        assertEquals(4L, response.getTransactionId());

        // Should never attempt to save a new transaction or new result
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(fraudResultRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Scenario 5: Rule with no matching strategy is skipped gracefully
    // ------------------------------------------------------------------
    @Test
    void processTransaction_ruleWithoutMatchingStrategy_isSkipped() {
        TransactionRequest request = buildRequest("txn-skip-1", new BigDecimal("50.00"));

        when(idempotencyService.findExisting("txn-skip-1")).thenReturn(Optional.empty());

        Transaction savedTransaction = new Transaction();
        savedTransaction.setId(5L);
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenReturn(savedTransaction);

        // GEO_ANOMALY rule exists but no strategy for it was registered in setUp()
        FraudRule geoRule = buildRule(RuleType.GEO_ANOMALY);
        when(fraudRuleRepository.findByEnabledTrue()).thenReturn(List.of(geoRule));

        when(fraudResultRepository.save(any(FraudResult.class))).thenAnswer(inv -> {
            FraudResult r = inv.getArgument(0);
            r.setId(5L);
            return r;
        });

        FraudResultResponse response = fraudDetectionService.processTransaction(request);

        assertEquals(TransactionStatus.SAFE, response.getStatus());
        assertEquals("", response.getReasonCodes());
    }

    // ------------------------------------------------------------------
    // Scenario 6: No enabled rules at all -> always SAFE
    // ------------------------------------------------------------------
    @Test
    void processTransaction_noEnabledRules_returnsSafe() {
        TransactionRequest request = buildRequest("txn-norules-1", new BigDecimal("99999.00"));

        when(idempotencyService.findExisting("txn-norules-1")).thenReturn(Optional.empty());

        Transaction savedTransaction = new Transaction();
        savedTransaction.setId(6L);
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenReturn(savedTransaction);

        when(fraudRuleRepository.findByEnabledTrue()).thenReturn(new ArrayList<>());

        when(fraudResultRepository.save(any(FraudResult.class))).thenAnswer(inv -> {
            FraudResult r = inv.getArgument(0);
            r.setId(6L);
            return r;
        });

        FraudResultResponse response = fraudDetectionService.processTransaction(request);

        assertEquals(TransactionStatus.SAFE, response.getStatus());
    }

    // Helper for cleaner matching in mocks that need eq() with equals-based objects
    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
