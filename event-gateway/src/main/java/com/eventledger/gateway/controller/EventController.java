package com.eventledger.gateway.controller;

import com.eventledger.gateway.model.EventRequest;
import com.eventledger.gateway.model.EventResponse;
import com.eventledger.gateway.service.AccountServiceClient;
import com.eventledger.gateway.service.AccountServiceClient.AccountServiceUnavailableException;
import com.eventledger.gateway.service.EventService;
import com.eventledger.gateway.service.EventService.CreateEventResult;
import com.eventledger.gateway.service.PendingEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);
    private final EventService eventService;
    private final AccountServiceClient accountServiceClient;
    private final PendingEventProcessor pendingEventProcessor;
    private final DataSource dataSource;

    public EventController(EventService eventService, AccountServiceClient accountServiceClient,
                           PendingEventProcessor pendingEventProcessor, DataSource dataSource) {
        this.eventService = eventService;
        this.accountServiceClient = accountServiceClient;
        this.pendingEventProcessor = pendingEventProcessor;
        this.dataSource = dataSource;
    }

    @PostMapping("/events")
    public ResponseEntity<?> createEvent(@RequestBody EventRequest request) {
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
            log.error("Account Service unavailable: {}", e.getMessage());
            Map<String, Object> body = new HashMap<>();
            body.put("error", "Account Service unavailable");
            body.put("detail", e.getMessage());
            body.put("queued", true);
            body.put("message", "Event queued and will be processed when Account Service recovers");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
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
        Map<String, Object> response = new HashMap<>();
        response.put("status", "healthy");
        response.put("service", "event-gateway");
        response.put("pendingEvents", pendingEventProcessor.getPendingCount());

        try (Connection conn = dataSource.getConnection()) {
            response.put("database", Map.of("status", "connected", "url", conn.getMetaData().getURL()));
        } catch (Exception e) {
            response.put("database", Map.of("status", "disconnected", "error", e.getMessage()));
        }
        return ResponseEntity.ok(response);
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
