# Fraud Detection Rule Engine

A Spring Boot backend that evaluates transactions in real time against configurable fraud rules using the Strategy Pattern, and classifies each transaction as SAFE, SUSPICIOUS, or FRAUD.

## Tech Stack
- Java 17
- Spring Boot 3.2.5
- Spring Data JPA
- MySQL
- Maven

## Project Structure
```
src/main/java/com/frauddetection/
├── FraudDetectionApplication.java   # main entry point
├── entity/                          # JPA entities: Transaction, FraudRule, FraudResult
├── enums/                           # TransactionStatus, RuleType
├── repository/                      # Spring Data JPA repositories
├── strategy/                        # Strategy Pattern: one class per fraud rule
├── service/                         # FraudDetectionService (engine), IdempotencyService
├── controller/                      # REST API controller
├── dto/                             # Request/response DTOs
└── config/                          # Global exception handling
```

## Setup

### 1. Database
Make sure MySQL is running locally. The app is configured to auto-create the database on startup via `createDatabaseIfNotExist=true` in the JDBC URL, but you can also create it manually:
```sql
CREATE DATABASE frauddb;
```

Update credentials in `src/main/resources/application.properties` if different from defaults (`root` / `root` on `localhost:3306`).

### 2. Build & Run
```bash
mvn clean install
mvn spring-boot:run
```

The app starts on `http://localhost:8080`.

On first run, Hibernate creates the tables (`ddl-auto=update`), and `data.sql` seeds four default fraud rules.

## Database Schema

**transactions**
| Column | Type | Notes |
|---|---|---|
| id | BIGINT (PK) | auto-increment |
| idempotency_key | VARCHAR (unique) | prevents duplicate processing |
| account_id | VARCHAR | |
| amount | DECIMAL | |
| country | VARCHAR | nullable |
| city | VARCHAR | nullable |
| success | BOOLEAN | |
| created_at | TIMESTAMP | |
| version | BIGINT | optimistic locking |

**fraud_rules**
| Column | Type | Notes |
|---|---|---|
| id | BIGINT (PK) | |
| rule_type | VARCHAR (unique) | HIGH_VALUE / FREQUENCY / GEO_ANOMALY / REPEATED_FAILURE |
| enabled | BOOLEAN | toggles the rule on/off without code changes |
| threshold_amount | DECIMAL | used by HIGH_VALUE |
| threshold_count | INT | used by FREQUENCY / REPEATED_FAILURE |
| window_minutes | INT | time window for FREQUENCY / GEO_ANOMALY / REPEATED_FAILURE |
| description | VARCHAR | |

**fraud_results**
| Column | Type | Notes |
|---|---|---|
| id | BIGINT (PK) | |
| transaction_id | BIGINT (FK, unique) | one result per transaction |
| status | VARCHAR | SAFE / SUSPICIOUS / FRAUD |
| reason_codes | VARCHAR | comma-separated triggered rule codes |
| evaluated_at | TIMESTAMP | |

## Fraud Scenarios (Strategy Pattern)

Each scenario is implemented as an independent class implementing `FraudCheckStrategy`, so new rules can be added without modifying existing code:

| Rule Type | Reason Code | Logic |
|---|---|---|
| HIGH_VALUE | HIGH_VALUE_TXN | amount > threshold_amount |
| FREQUENCY | VELOCITY_BREACH | count of transactions by account in window_minutes >= threshold_count |
| GEO_ANOMALY | GEO_ANOMALY_DETECTED | account has transactions from more than one country within window_minutes |
| REPEATED_FAILURE | REPEATED_FAILURES | count of failed transactions by account in window_minutes >= threshold_count |

### Categorization Logic
- 0 rules triggered → SAFE
- 1 rule triggered → SUSPICIOUS
- 2+ rules triggered → FRAUD

## API Documentation

### POST /api/transactions
Submits a transaction for fraud evaluation.

**Request Body**
```json
{
  "idempotencyKey": "txn-001",
  "accountId": "acc-123",
  "amount": 15000.00,
  "country": "US",
  "city": "New York",
  "success": true
}
```

**Response — 201 Created**
```json
{
  "transactionId": 1,
  "status": "SUSPICIOUS",
  "reasonCodes": "HIGH_VALUE_TXN",
  "evaluatedAt": "2026-07-16T10:15:30"
}
```

**Response — 400 Bad Request** (validation failure)
```json
{
  "amount": "amount must be positive",
  "accountId": "accountId is required"
}
```

### Idempotency
If the same `idempotencyKey` is submitted again, the API returns the original stored result instead of reprocessing — safe for client retries.

### Concurrency Safety
- `Transaction.version` uses JPA optimistic locking (`@Version`) to prevent lost updates.
- The unique constraint on `idempotencyKey` combined with catching `DataIntegrityViolationException` handles the race where two identical requests arrive at nearly the same instant.

## Sample Test Requests

**Safe transaction**
```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"txn-101","accountId":"acc-1","amount":50.00,"country":"US","success":true}'
```

**High-value (SUSPICIOUS)**
```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"txn-102","accountId":"acc-2","amount":25000.00,"country":"US","success":true}'
```

**Repeated failures (submit 3+ times with success:false, same accountId)**
```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"txn-103","accountId":"acc-3","amount":20.00,"country":"US","success":false}'
```

**Geo anomaly (submit from different countries, same accountId, within 15 min)**
```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"txn-104","accountId":"acc-4","amount":20.00,"country":"US","success":true}'

curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"txn-105","accountId":"acc-4","amount":20.00,"country":"IN","success":true}'
```

## Extending with a New Rule
1. Add a new value to `RuleType` enum.
2. Create a class implementing `FraudCheckStrategy`, annotate with `@Component`.
3. Insert a corresponding row into `fraud_rules`.

No changes needed in `FraudDetectionService` — it auto-discovers all `FraudCheckStrategy` beans via Spring's dependency injection.
