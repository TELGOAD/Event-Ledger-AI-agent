package com.eventledger.gateway.controller;

import com.eventledger.gateway.model.EventRequest;
import com.eventledger.gateway.model.EventResponse;
import com.eventledger.gateway.service.AccountServiceClient;
import com.eventledger.gateway.service.AccountServiceClient.AccountServiceUnavailableException;
import com.eventledger.gateway.service.EventService;
import com.eventledger.gateway.service.EventService.CreateEventResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);
    private final EventService eventService;
    private final AccountServiceClient accountServiceClient;

    public EventController(EventService eventService, AccountServiceClient accountServiceClient) {
        this.eventService = eventService;
        this.accountServiceClient = accountServiceClient;
    }

    @PostMapping("/events")
    public ResponseEntity<?> createEvent(@RequestBody EventRequest request) {
        // Validation
        String validationError = validate(request);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", validationError));
        }

        try {
            CreateEventResult result = eventService.createEvent(request);
            if (result.isDuplicate()) {
                return ResponseEntity.ok(result.response());
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
        } catch (AccountServiceUnavailableException e) {
            log.error("Account Service unavailable during event creation: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Account Service unavailable", "detail", e.getMessage()));
        }
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<?> getEvent(@PathVariable String eventId) {
        return eventService.getEvent(eventId)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Event not found", "eventId", eventId)));
    }

    @GetMapping("/events")
    public ResponseEntity<List<EventResponse>> listEvents(@RequestParam(required = false) String account) {
        return ResponseEntity.ok(eventService.listEvents(account));
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<?> getBalance(@PathVariable String accountId) {
        try {
            Map<String, Object> balance = accountServiceClient.getBalance(accountId);
            return ResponseEntity.ok(balance);
        } catch (AccountServiceUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Account Service unavailable for balance query"));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "healthy",
            "service", "event-gateway"
        ));
    }

    private String validate(EventRequest request) {
        if (request.eventId() == null || request.eventId().isBlank()) return "eventId is required";
        if (request.accountId() == null || request.accountId().isBlank()) return "accountId is required";
        if (request.type() == null || (!request.type().equals("CREDIT") && !request.type().equals("DEBIT")))
            return "type must be CREDIT or DEBIT";
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0)
            return "amount must be greater than 0";
        if (request.currency() == null || request.currency().isBlank()) return "currency is required";
        if (request.eventTimestamp() == null || request.eventTimestamp().isBlank()) return "eventTimestamp is required";
        try {
            Instant.parse(request.eventTimestamp());
        } catch (Exception e) {
            return "eventTimestamp must be valid ISO 8601 format";
        }
        return null;
    }
}
