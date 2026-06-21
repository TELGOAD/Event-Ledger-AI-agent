package com.eventledger.gateway.model;

import java.math.BigDecimal;
import java.util.Map;

public record EventResponse(
    String eventId,
    String accountId,
    String type,
    BigDecimal amount,
    String currency,
    String eventTimestamp,
    Map<String, Object> metadata,
    String status,
    String createdAt
) {}
