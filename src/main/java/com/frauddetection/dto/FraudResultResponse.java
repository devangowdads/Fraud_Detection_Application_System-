package com.frauddetection.dto;

import com.frauddetection.enums.TransactionStatus;

import java.time.LocalDateTime;

public class FraudResultResponse {

    private Long transactionId;
    private TransactionStatus status;
    private String reasonCodes;
    private LocalDateTime evaluatedAt;

    public FraudResultResponse(Long transactionId, TransactionStatus status,
                                String reasonCodes, LocalDateTime evaluatedAt) {
        this.transactionId = transactionId;
        this.status = status;
        this.reasonCodes = reasonCodes;
        this.evaluatedAt = evaluatedAt;
    }

    // Getters
    public Long getTransactionId() { return transactionId; }
    public TransactionStatus getStatus() { return status; }
    public String getReasonCodes() { return reasonCodes; }
    public LocalDateTime getEvaluatedAt() { return evaluatedAt; }
}