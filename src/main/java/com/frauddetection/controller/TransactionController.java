package com.frauddetection.controller;

import com.frauddetection.dto.FraudResultResponse;
import com.frauddetection.dto.TransactionRequest;
import com.frauddetection.service.FraudDetectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transactions")
@Tag(name = "Transactions", description = "Submit and evaluate transactions for fraud")
public class TransactionController {

    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);

    private final FraudDetectionService fraudDetectionService;

    public TransactionController(FraudDetectionService fraudDetectionService) {
        this.fraudDetectionService = fraudDetectionService;
    }

    @Operation(
            summary = "Submit a transaction for fraud evaluation",
            description = "Evaluates the transaction against all enabled fraud rules and returns "
                    + "a classification of SAFE, SUSPICIOUS, or FRAUD along with triggered reason codes. "
                    + "Safe to retry with the same idempotencyKey."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Transaction evaluated and stored successfully"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body")
    })
    @PostMapping
    public ResponseEntity<FraudResultResponse> submitTransaction(
            @Valid @RequestBody TransactionRequest request) {

        logger.info("Received transaction request | accountId={} idempotencyKey={} amount={}",
                request.getAccountId(), request.getIdempotencyKey(), request.getAmount());

        FraudResultResponse response = fraudDetectionService.processTransaction(request);

        logger.info("Transaction evaluated | transactionId={} status={} reasonCodes={}",
                response.getTransactionId(), response.getStatus(), response.getReasonCodes());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}