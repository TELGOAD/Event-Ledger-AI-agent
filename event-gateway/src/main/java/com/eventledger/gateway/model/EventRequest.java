package com.eventledger.gateway.model;

import java.math.BigDecimal;
import java.util.Map;

public record EventRequest(
    String eventId,
    String accountId,
    String type,
    BigDecimal amount,
    String currency,
    String eventTimestamp,
    Map<String, Object> metadata
) {}
