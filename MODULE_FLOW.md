# Fraud Detection Engine — Module Flow Documentation

This document walks through exactly which module/class is invoked, in what order, for each major flow in the system.

---

## Flow 1: New Transaction Submission (Happy Path)

**Trigger:** Client sends `POST /api/transactions` with a new `idempotencyKey`.

```
1. TransactionController.submitTransaction()
   │  - @Valid triggers Bean Validation on TransactionRequest
   │  - Logs incoming request (accountId, idempotencyKey, amount)
   ▼
2. FraudDetectionService.processTransaction()
   │
   ├─ 2a. IdempotencyService.findExisting(idempotencyKey)
   │       → TransactionRepository.findByIdempotencyKey()
   │       → returns Optional.empty() (new transaction, not a duplicate)
   │
   ├─ 2b. Build new Transaction entity from request fields
   │
   ├─ 2c. TransactionRepository.saveAndFlush(transaction)
   │       → INSERT into `transactions` table, immediately flushed
   │       → transaction.id is now populated
   │
   ├─ 2d. FraudRuleRepository.findByEnabledTrue()
   │       → SELECT * FROM fraud_rules WHERE enabled = true
   │       → returns List<FraudRule> (e.g., HIGH_VALUE, FREQUENCY, GEO_ANOMALY, REPEATED_FAILURE)
   │
   ├─ 2e. For each FraudRule in the list:
   │       │
   │       ├─ Look up matching strategy: strategyMap.get(rule.getRuleType())
   │       │
   │       ├─ HighValueStrategy.evaluate(transaction, rule)
   │       │     → compares transaction.amount vs rule.thresholdAmount
   │       │     → returns Optional<"HIGH_VALUE_TXN"> or Optional.empty()
   │       │
   │       ├─ FrequencyStrategy.evaluate(transaction, rule)
   │       │     → TransactionRepository.findRecentByAccount(accountId, since)
   │       │     → counts recent transactions vs rule.thresholdCount
   │       │     → returns Optional<"VELOCITY_BREACH"> or Optional.empty()
   │       │
   │       ├─ GeoAnomalyStrategy.evaluate(transaction, rule)
   │       │     → TransactionRepository.findRecentByAccount(accountId, since)
   │       │     → checks for a different country in the recent window
   │       │     → returns Optional<"GEO_ANOMALY_DETECTED"> or Optional.empty()
   │       │
   │       └─ RepeatedFailureStrategy.evaluate(transaction, rule)
   │             → TransactionRepository.findRecentFailuresByAccount(accountId, since)
   │             → counts recent failures vs rule.thresholdCount
   │             → returns Optional<"REPEATED_FAILURES"> or Optional.empty()
   │
   ├─ 2f. Collect all triggered reason codes into a List<String>
   │
   ├─ 2g. categorize(reasons)
   │       0 reasons  → TransactionStatus.SAFE
   │       1 reason   → TransactionStatus.SUSPICIOUS
   │       2+ reasons → TransactionStatus.FRAUD
   │
   ├─ 2h. Build FraudResult entity (status + comma-joined reasonCodes)
   │
   ├─ 2i. FraudResultRepository.save(result)
   │       → INSERT into `fraud_results` table
   │
   └─ 2j. Build FraudResultResponse DTO from the saved result
   ▼
3. TransactionController
   │  - Logs final classification (transactionId, status, reasonCodes)
   │  - Returns 201 Created with FraudResultResponse body
   ▼
4. Client receives JSON response
```

---

## Flow 2: Duplicate Request (Idempotency)

**Trigger:** Client sends `POST /api/transactions` with an `idempotencyKey` that was already processed.

```
1. TransactionController.submitTransaction()
   ▼
2. FraudDetectionService.processTransaction()
   │
   ├─ 2a. IdempotencyService.findExisting(idempotencyKey)
   │       → TransactionRepository.findByIdempotencyKey()
   │       → returns Optional<Transaction> (FOUND — already processed)
   │
   ├─ 2b. findResultForTransaction(existingTransaction.getId())
   │       → FraudResultRepository.findAll(), matched by transaction id
   │       → returns the previously stored FraudResult
   │
   └─ 2c. toResponse(existingResult)
   ▼
3. TransactionController returns 201 Created
   with the ORIGINAL stored result (no new transaction, no rule re-evaluation)
```

**Key point:** Steps 2b–2i from Flow 1 (persisting a new transaction, running strategies) are **skipped entirely**. This guarantees retries never double-process a transaction.

---

## Flow 3: Concurrent Duplicate Requests (Race Condition)

**Trigger:** Two requests with the same `idempotencyKey` arrive at nearly the same instant, both passing the idempotency check before either has committed.

```
Thread A                                    Thread B
────────                                    ────────
1. findExisting(key) → empty                1. findExisting(key) → empty
2. saveAndFlush(transaction) → SUCCESS       2. saveAndFlush(transaction) → FAILS
   (unique constraint on idempotency_key)       (DataIntegrityViolationException:
                                                  duplicate key violation)
3. Proceeds through strategy evaluation      3. catch (DataIntegrityViolationException e)
   normally (Flow 1, steps 2d–2j)               │
                                                 ├─ idempotencyService.findExisting(key)
                                                 │    → now returns Thread A's transaction
                                                 │
                                                 ├─ findResultForTransaction(id)
                                                 │    → Thread A's FraudResult
                                                 │    (may need a brief retry/timing
                                                 │     window if Thread A hasn't
                                                 │     finished saving its result yet)
                                                 │
                                                 └─ returns Thread A's result to the client
```

**Key point:** `saveAndFlush()` is critical here — it forces the unique constraint check to happen immediately rather than at transaction commit, allowing Thread B to detect and gracefully resolve the conflict within the same request instead of throwing an unhandled error.

---

## Flow 4: Validation Failure

**Trigger:** Client sends a request missing required fields or with an invalid amount.

```
1. TransactionController.submitTransaction()
   │  - @Valid triggers Bean Validation on TransactionRequest
   │  - Validation FAILS (e.g., amount <= 0, accountId blank)
   │  - MethodArgumentNotValidException is thrown
   │  (FraudDetectionService.processTransaction() is NEVER reached)
   ▼
2. GlobalExceptionHandler.handleValidation()
   │  - Extracts field errors from the exception
   │  - Builds Map<String, String> of field → error message
   ▼
3. Returns 400 Bad Request with the error map
```

---

## Flow 5: Rule Configuration Change (No Code Deployment)

**Trigger:** An admin wants to adjust a threshold or disable a rule.

```
1. Direct database update:
   UPDATE fraud_rules SET threshold_amount = 5000.00 WHERE rule_type = 'HIGH_VALUE';
   -- or --
   UPDATE fraud_rules SET enabled = false WHERE rule_type = 'GEO_ANOMALY';

2. No application restart needed.

3. Next incoming transaction:
   FraudDetectionService.processTransaction()
     → FraudRuleRepository.findByEnabledTrue()
       → reflects the updated threshold / enabled status immediately,
         since rules are read fresh from the DB on every request
```

---

## Flow 6: Adding a Brand-New Fraud Rule (Developer Flow)

**Trigger:** A developer wants to add a new fraud scenario, e.g., "blacklisted merchant category."

```
1. Add new enum value:
   RuleType.BLACKLISTED_MERCHANT

2. Create new strategy class:
   @Component
   public class BlacklistedMerchantStrategy implements FraudCheckStrategy {
       public RuleType getRuleType() { return RuleType.BLACKLISTED_MERCHANT; }
       public Optional<String> evaluate(Transaction t, FraudRule rule) { ... }
   }

3. Spring auto-discovers the new @Component at startup:
   FraudDetectionService constructor
     → receives List<FraudCheckStrategy> (now includes the new strategy)
     → builds strategyMap including BLACKLISTED_MERCHANT → BlacklistedMerchantStrategy

4. Insert a new row into fraud_rules:
   INSERT INTO fraud_rules (rule_type, enabled, ...) VALUES ('BLACKLISTED_MERCHANT', true, ...);

5. From this point on, Flow 1 automatically includes this new rule
   in step 2e — NO changes needed to FraudDetectionService,
   TransactionController, or any repository.
```

---

## Module Interaction Summary

| Module | Depends On | Used By |
|---|---|---|
| `TransactionController` | `FraudDetectionService` | Client (HTTP) |
| `FraudDetectionService` | `TransactionRepository`, `FraudRuleRepository`, `FraudResultRepository`, `IdempotencyService`, `List<FraudCheckStrategy>` | `TransactionController` |
| `IdempotencyService` | `TransactionRepository` | `FraudDetectionService` |
| `HighValueStrategy` | — (stateless, uses only passed-in Transaction/FraudRule) | `FraudDetectionService` (via strategyMap) |
| `FrequencyStrategy` | `TransactionRepository` | `FraudDetectionService` (via strategyMap) |
| `GeoAnomalyStrategy` | `TransactionRepository` | `FraudDetectionService` (via strategyMap) |
| `RepeatedFailureStrategy` | `TransactionRepository` | `FraudDetectionService` (via strategyMap) |
| `GlobalExceptionHandler` | — | Intercepts exceptions from `TransactionController` |
| `OpenApiConfig` | — | Powers Swagger UI generation |
