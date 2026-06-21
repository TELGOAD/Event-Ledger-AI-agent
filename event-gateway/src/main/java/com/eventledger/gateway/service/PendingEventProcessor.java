package com.eventledger.gateway.service;

import com.eventledger.gateway.model.Event;
import com.eventledger.gateway.model.EventRequest;
import com.eventledger.gateway.model.PendingEvent;
import com.eventledger.gateway.repository.EventRepository;
import com.eventledger.gateway.repository.PendingEventRepository;
import com.eventledger.gateway.service.AccountServiceClient.AccountServiceUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class PendingEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(PendingEventProcessor.class);
    private static final int MAX_RETRIES = 5;

    private final PendingEventRepository pendingEventRepository;
    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;

    public PendingEventProcessor(PendingEventRepository pendingEventRepository,
                                 EventRepository eventRepository,
                                 AccountServiceClient accountServiceClient,
                                 ObjectMapper objectMapper) {
        this.pendingEventRepository = pendingEventRepository;
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 15000)
    public void processPendingEvents() {
        List<PendingEvent> pending = pendingEventRepository.findByRetryCountLessThanOrderByCreatedAtAsc(MAX_RETRIES);
        if (pending.isEmpty()) return;

        log.info("Processing {} pending events", pending.size());

        for (PendingEvent pe : pending) {
            try {
                EventRequest request = objectMapper.readValue(pe.getPayload(), EventRequest.class);

                if (eventRepository.findByEventId(request.eventId()).isPresent()) {
                    pendingEventRepository.delete(pe);
                    continue;
                }

                accountServiceClient.applyTransaction(request.accountId(), request);

                String metadataJson = request.metadata() != null
                    ? objectMapper.writeValueAsString(request.metadata()) : null;
                Event event = new Event(request.eventId(), request.accountId(), request.type(),
                    request.amount(), request.currency(), Instant.parse(request.eventTimestamp()), metadataJson);
                eventRepository.save(event);
                pendingEventRepository.delete(pe);
                log.info("Pending event processed: eventId={}", request.eventId());

            } catch (AccountServiceUnavailableException e) {
                pe.incrementRetry();
                pendingEventRepository.save(pe);
                log.warn("Account Service still unavailable, retry count={}", pe.getRetryCount());
                break;
            } catch (Exception e) {
                pe.incrementRetry();
                pendingEventRepository.save(pe);
                log.error("Error processing pending event: {}", e.getMessage());
            }
        }
    }

    public long getPendingCount() {
        return pendingEventRepository.count();
    }
}
