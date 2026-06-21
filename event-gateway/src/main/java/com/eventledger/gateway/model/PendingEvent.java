package com.eventledger.gateway.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "pending_events")
public class PendingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private Instant createdAt;

    public PendingEvent() {}

    public PendingEvent(String payload, String accountId) {
        this.payload = payload;
        this.accountId = accountId;
        this.retryCount = 0;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getPayload() { return payload; }
    public String getAccountId() { return accountId; }
    public int getRetryCount() { return retryCount; }
    public Instant getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setPayload(String payload) { this.payload = payload; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public void incrementRetry() { this.retryCount++; }
}
