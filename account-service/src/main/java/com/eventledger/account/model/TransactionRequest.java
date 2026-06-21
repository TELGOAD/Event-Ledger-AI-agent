package com.eventledger.account.model;

import java.math.BigDecimal;

public record TransactionRequest(
    String eventId,
    String type,
    BigDecimal amount,
    String currency,
    String eventTimestamp
) {}
