# Fraud Detection Engine — API Documentation

Base URL: `http://localhost:8081`

Interactive Swagger UI: `http://localhost:8081/swagger-ui.html`
OpenAPI JSON spec: `http://localhost:8081/api-docs`

---

## 1. Submit Transaction for Fraud Evaluation

Evaluates a transaction against all enabled fraud rules in real time and returns a classification.

### Endpoint
```
POST /api/transactions
```

### Headers
| Header | Value |
|---|---|
| Content-Type | application/json |

### Request Body

| Field | Type | Required | Description |
|---|---|---|---|
| idempotencyKey | string | Yes | Unique key to prevent duplicate processing on retries |
| accountId | string | Yes | Identifier of the account making the transaction |
| amount | decimal | Yes | Transaction amount. Must be > 0 |
| country | string | No | ISO country code / name of transaction origin |
| city | string | No | City of transaction origin |
| success | boolean | No (default: true) | Whether the transaction attempt succeeded |

**Example Request**
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

### Success Response — `201 Created`

| Field | Type | Description |
|---|---|---|
| transactionId | number | Internal ID of the persisted transaction |
| status | string | One of SAFE, SUSPICIOUS, FRAUD |
| reasonCodes | string | Comma-separated codes for triggered rules (empty if none) |
| evaluatedAt | string (ISO datetime) | Timestamp of evaluation |

```json
{
  "transactionId": 1,
  "status": "SUSPICIOUS",
  "reasonCodes": "HIGH_VALUE_TXN",
  "evaluatedAt": "2026-07-16T10:15:30"
}
```

### Validation Error Response — `400 Bad Request`

```json
{
  "amount": "amount must be positive",
  "accountId": "accountId is required"
}
```

---

## 2. Classification Logic

| Rules Triggered | Resulting Status |
|---|---|
| 0 | SAFE |
| 1 | SUSPICIOUS |
| 2 or more | FRAUD |

---

## 3. Fraud Rules Reference

| Rule Type | Reason Code | Trigger Condition | Configurable Fields |
|---|---|---|---|
| HIGH_VALUE | HIGH_VALUE_TXN | `amount` exceeds `thresholdAmount` | thresholdAmount |
| FREQUENCY | VELOCITY_BREACH | Transactions by same `accountId` within `windowMinutes` reaches `thresholdCount` | thresholdCount, windowMinutes |
| GEO_ANOMALY | GEO_ANOMALY_DETECTED | Account has transactions from more than one country within `windowMinutes` | windowMinutes |
| REPEATED_FAILURE | REPEATED_FAILURES | Failed transactions by same `accountId` within `windowMinutes` reaches `thresholdCount` | thresholdCount, windowMinutes |

### Default Seed Values (from `data.sql`)

| Rule Type | thresholdAmount | thresholdCount | windowMinutes |
|---|---|---|---|
| HIGH_VALUE | 10000.00 | – | – |
| FREQUENCY | – | 5 | 10 |
| GEO_ANOMALY | – | – | 15 |
| REPEATED_FAILURE | – | 3 | 30 |

---

## 4. Idempotency Behavior

Submitting the same `idempotencyKey` twice returns the **original stored result** instead of re-processing — safe for client retries after timeouts.

---

## 5. Concurrency Safety

- Unique DB constraint on `idempotencyKey`.
- Optimistic locking via `@Version` on `Transaction`.
- Simultaneous duplicate requests: the losing request's `DataIntegrityViolationException` is caught and resolved by returning the winner's stored result.

---

## 6. Error Reference

| HTTP Status | Cause |
|---|---|
| 201 Created | Transaction successfully evaluated and stored |
| 400 Bad Request | Request validation failed |
| 500 Internal Server Error | Unexpected server-side failure |

---

## 7. Sample cURL Requests

**Safe transaction**
```bash
curl -X POST http://localhost:8081/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"txn-101","accountId":"acc-1","amount":50.00,"country":"US","success":true}'
```

**High-value (SUSPICIOUS)**
```bash
curl -X POST http://localhost:8081/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"txn-102","accountId":"acc-2","amount":25000.00,"country":"US","success":true}'
```

**Validation failure**
```bash
curl -X POST http://localhost:8081/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"amount":-10}'
```

---

## 8. Logging

INFO level: request received, final classification. DEBUG level: per-rule evaluation detail. Output goes to console and `logs/fraud-detection-engine.log` (rolling, 10MB/file, 7-day retention).

---

## 9. Extending with New Rules

1. Add a value to `RuleType` enum.
2. Implement `FraudCheckStrategy`, annotate with `@Component`.
3. Insert a row into `fraud_rules`.

No controller/service changes needed — new strategies are auto-discovered via Spring DI.
