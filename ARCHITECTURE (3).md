# Fraud Detection Engine — Architecture Document

## 1. Overview

The Fraud Detection Engine is a Spring Boot backend service that evaluates financial transactions in real time against a set of configurable fraud rules, and classifies each transaction as **SAFE**, **SUSPICIOUS**, or **FRAUD**. The system is built around a layered architecture combined with the **Strategy Pattern** to keep fraud rules pluggable and independently testable.

---

## 2. High-Level Architecture

```
                        ┌─────────────────────────┐
                        │        Client            │
                        │ (Postman / Frontend /    │
                        │   Swagger UI)             │
                        └───────────┬───────────────┘
                                    │ HTTP (JSON)
                                    ▼
                        ┌─────────────────────────┐
                        │   TransactionController   │
                        │   (REST API Layer)        │
                        └───────────┬───────────────┘
                                    │
                                    ▼
                        ┌─────────────────────────┐
                        │  FraudDetectionService    │
                        │  (Core Orchestration)     │
                        └───────┬─────────┬─────────┘
                                │         │
              ┌─────────────────┘         └──────────────────┐
              ▼                                               ▼
  ┌─────────────────────────┐               ┌───────────────────────────┐
  │   IdempotencyService      │               │  Strategy Map (Rule Engine)│
  │  (duplicate-request check)│               │  HighValueStrategy         │
  └───────────┬───────────────┘               │  FrequencyStrategy         │
              │                               │  GeoAnomalyStrategy        │
              ▼                               │  RepeatedFailureStrategy   │
  ┌─────────────────────────┐               └───────────────┬────────────┘
  │  Repository Layer         │                               │
  │  (Spring Data JPA)        │◄──────────────────────────────┘
  │  TransactionRepository    │
  │  FraudRuleRepository      │
  │  FraudResultRepository    │
  └───────────┬───────────────┘
              │
              ▼
  ┌─────────────────────────┐
  │        MySQL Database     │
  │  transactions              │
  │  fraud_rules                │
  │  fraud_results               │
  └─────────────────────────┘
```

---

## 3. Layered Design

| Layer | Responsibility | Key Classes |
|---|---|---|
| **Controller** | Accepts HTTP requests, validates input, returns HTTP responses | `TransactionController` |
| **Service** | Orchestrates the fraud evaluation workflow, handles idempotency and concurrency | `FraudDetectionService`, `IdempotencyService` |
| **Strategy** | Encapsulates individual fraud rule logic, one class per rule type | `FraudCheckStrategy` (interface) + 4 implementations |
| **Repository** | Data access via Spring Data JPA | `TransactionRepository`, `FraudRuleRepository`, `FraudResultRepository` |
| **Entity** | JPA-mapped domain objects | `Transaction`, `FraudRule`, `FraudResult` |
| **DTO** | Request/response contracts, decoupled from entities | `TransactionRequest`, `FraudResultResponse` |
| **Config** | Cross-cutting concerns | `GlobalExceptionHandler`, `OpenApiConfig` |

This separation ensures each layer has a single responsibility and can be modified independently — e.g., adding a new fraud rule never touches the controller or repository layers.

---

## 4. Design Patterns Used

### 4.1 Strategy Pattern (core of the rule engine)

Each fraud scenario is implemented as an independent class implementing the `FraudCheckStrategy` interface:

```java
public interface FraudCheckStrategy {
    RuleType getRuleType();
    Optional<String> evaluate(Transaction transaction, FraudRule rule);
}
```

Spring automatically collects all `@Component`-annotated implementations into a `List<FraudCheckStrategy>`, which `FraudDetectionService` converts into a `Map<RuleType, FraudCheckStrategy>` at startup. This means:

- Adding a new fraud rule requires **zero changes** to `FraudDetectionService`.
- Each rule can be unit tested independently.
- Rules can be enabled/disabled purely through database configuration (`fraud_rules.enabled`), with no redeployment.

### 4.2 Repository Pattern (Spring Data JPA)

All database access goes through repository interfaces extending `JpaRepository`, keeping SQL/JPQL isolated from business logic.

### 4.3 DTO Pattern

`TransactionRequest` and `FraudResultResponse` decouple the API contract from the internal JPA entity structure, so entity changes don't break API consumers and vice versa.

---

## 5. Data Flow (Request Lifecycle)

1. Client sends `POST /api/transactions` with transaction details.
2. `TransactionController` validates the request body (`@Valid`) and logs the incoming request.
3. `FraudDetectionService.processTransaction()`:
   a. Checks `IdempotencyService` for an existing transaction with the same `idempotencyKey`.
      - If found → returns the already-stored `FraudResult` immediately (no reprocessing).
   b. Persists the new `Transaction` via `saveAndFlush()` (immediate DB write, not deferred).
      - If a unique constraint violation occurs (race condition from concurrent duplicate requests), the conflict is resolved by fetching and returning the other thread's result.
   c. Fetches all enabled `FraudRule`s from the database.
   d. For each rule, looks up its corresponding `FraudCheckStrategy` from the strategy map and evaluates the transaction.
   e. Collects all triggered reason codes.
   f. Categorizes the transaction: 0 triggers → SAFE, 1 → SUSPICIOUS, 2+ → FRAUD.
   g. Persists the `FraudResult`, linked to the `Transaction`.
4. Controller returns `201 Created` with the classification result.

---

## 6. Database Schema (Entity Relationships)

```
┌────────────────────┐         ┌────────────────────┐
│    transactions      │ 1     1 │   fraud_results      │
│─────────────────────│─────────│─────────────────────│
│ id (PK)              │         │ id (PK)               │
│ idempotency_key (UQ) │         │ transaction_id (FK,UQ)│
│ account_id           │         │ status                 │
│ amount                │         │ reason_codes            │
│ country               │         │ evaluated_at             │
│ city                  │         └────────────────────┘
│ success               │
│ created_at            │
│ version (optimistic   │
│   locking)            │
└────────────────────┘

┌────────────────────┐
│    fraud_rules        │   (independent, read by strategies at evaluation time)
│─────────────────────│
│ id (PK)               │
│ rule_type (UQ)        │
│ enabled                │
│ threshold_amount       │
│ threshold_count        │
│ window_minutes         │
│ description             │
└────────────────────┘
```

- `transactions.idempotency_key` — unique constraint, backbone of the idempotency mechanism.
- `transactions.version` — `@Version` field for JPA optimistic locking, protecting against concurrent update conflicts.
- `fraud_results.transaction_id` — one-to-one, unique, ensures exactly one result per transaction.
- `fraud_rules.rule_type` — unique, one row per rule type; `enabled` flag allows toggling rules without code changes.

---

## 7. Concurrency & Idempotency Strategy

| Concern | Mechanism |
|---|---|
| Duplicate client retries | `idempotencyKey` lookup before processing; returns existing result if found |
| Simultaneous duplicate requests (race condition) | Unique DB constraint on `idempotencyKey` + `saveAndFlush()` forces immediate constraint check; `DataIntegrityViolationException` is caught and resolved by returning the other thread's result |
| Concurrent updates to the same transaction | `@Version` field enables JPA optimistic locking |

`saveAndFlush()` (rather than `save()`) is used deliberately so the unique constraint violation surfaces **immediately**, within the same request, rather than being deferred to end-of-transaction commit — this is what allows the race-condition handling to work correctly.

---

## 8. Cross-Cutting Concerns

| Concern | Implementation |
|---|---|
| **Validation** | Bean Validation (`@NotBlank`, `@DecimalMin`, `@NotNull`) on `TransactionRequest`, enforced via `@Valid` |
| **Error Handling** | `GlobalExceptionHandler` (`@RestControllerAdvice`) converts validation failures into structured 400 responses |
| **Logging** | SLF4J loggers in controller, service, and all strategy classes; INFO for request/response lifecycle, DEBUG for per-rule evaluation detail; output to console + rolling log file |
| **API Documentation** | springdoc-openapi generates Swagger UI (`/swagger-ui.html`) and OpenAPI JSON (`/api-docs`) directly from controller annotations |
| **Transactions** | `@Transactional` on `processTransaction()` ensures the transaction record and its fraud result are persisted atomically |

---

## 9. Technology Stack

| Concern | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2.5 |
| Persistence | Spring Data JPA (Hibernate) |
| Database | MySQL |
| Build Tool | Maven |
| API Docs | springdoc-openapi (Swagger UI) |
| Logging | SLF4J + Logback |
| Testing | JUnit 5 + Mockito |

---

## 10. Extensibility

The architecture is deliberately designed so that adding a new fraud scenario requires touching only two things:

1. A new `FraudCheckStrategy` implementation (`@Component`).
2. A new row in the `fraud_rules` table.

No changes to `FraudDetectionService`, the controller, or the database schema are required — the strategy map is built dynamically at application startup via Spring's dependency injection.
