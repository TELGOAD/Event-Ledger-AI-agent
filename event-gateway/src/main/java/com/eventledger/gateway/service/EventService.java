package com.eventledger.gateway.service;

import com.eventledger.gateway.model.Event;
import com.eventledger.gateway.model.EventRequest;
import com.eventledger.gateway.model.EventResponse;
import com.eventledger.gateway.model.PendingEvent;
import com.eventledger.gateway.repository.EventRepository;
import com.eventledger.gateway.repository.PendingEventRepository;
import com.eventledger.gateway.service.AccountServiceClient.AccountServiceUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final PendingEventRepository pendingEventRepository;
    private final ObjectMapper objectMapper;

    public EventService(EventRepository eventRepository, AccountServiceClient accountServiceClient,
                        PendingEventRepository pendingEventRepository, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.pendingEventRepository = pendingEventRepository;
        this.objectMapper = objectMapper;
    }

    public record CreateEventResult(EventResponse response, boolean isDuplicate) {}

    public CreateEventResult createEvent(EventRequest request) {
        // Idempotency check
        Optional<Event> existing = eventRepository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            log.info("Duplicate event detected: eventId={}", request.eventId());
            return new CreateEventResult(toResponse(existing.get()), true);
        }

        String metadataJson = null;
        if (request.metadata() != null) {
            try {
                metadataJson = objectMapper.writeValueAsString(request.metadata());
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize metadata", e);
            }
        }

        try {
            // Call Account Service
            accountServiceClient.applyTransaction(request.accountId(), request);

            // Store locally on success
            Event event = new Event(request.eventId(), request.accountId(), request.type(),
                request.amount(), request.currency(), Instant.parse(request.eventTimestamp()), metadataJson);
            eventRepository.save(event);
            log.info("Event stored: eventId={}, accountId={}", request.eventId(), request.accountId());
            return new CreateEventResult(toResponse(event), false);

        } catch (AccountServiceUnavailableException e) {
            // Async fallback: queue locally for later processing
            try {
                String payload = objectMapper.writeValueAsString(request);
                pendingEventRepository.save(new PendingEvent(payload, request.accountId()));
                log.warn("Event queued for retry: eventId={}", request.eventId());
            } catch (JsonProcessingException ex) {
                log.error("Failed to queue event: {}", ex.getMessage());
            }
            throw e;
        }
    }

    public Optional<EventResponse> getEvent(String eventId) {
        return eventRepository.findByEventId(eventId).map(this::toResponse);
    }

    public List<EventResponse> listEvents(String accountId) {
        List<Event> events;
        if (accountId != null) {
            events = eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId);
        } else {
            events = eventRepository.findAll();
        }
        return events.stream().map(this::toResponse).toList();
    }

    private EventResponse toResponse(Event event) {
        Map<String, Object> metadata = null;
        if (event.getMetadata() != null) {
            try {
                metadata = objectMapper.readValue(event.getMetadata(), new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize metadata", e);
            }
        }
        return new EventResponse(
            event.getEventId(), event.getAccountId(), event.getType(),
            event.getAmount(), event.getCurrency(), event.getEventTimestamp().toString(),
            metadata, event.getStatus(), event.getCreatedAt().toString()
        );
    }
}
