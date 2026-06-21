# Event Ledger System

A distributed event ledger system composed of two microservices that process financial transaction events with attention to observability, resiliency, and operational readiness.

## Architecture Overview

```
                          ┌──────────────────────┐
Browser / Client ──────→  │  Event Gateway API    │  (port 8080)
                          │  - Event ingestion    │
                          │  - Idempotency check  │
                          │  - Validation         │
                          │  - Rate limiting      │
                          │  - Circuit breaker    │
                          │  - Async fallback     │
                          │  - H2 (events store)  │
                          └──────┬───────────────┘
                                 │ REST (sync) + OpenTelemetry trace propagation
                                 ▼
                          ┌──────────────────────┐
                          │  Account Service      │  (port 8081)
                          │  - Balance mgmt       │
                          │  - Transaction store  │
                          │  - H2 (accounts)      │
                          └──────────────────────┘
                                 
                          ┌──────────────────────┐
                          │  Jaeger               │  (port 16686 UI)
                          │  - Trace visualization│
                          └──────────────────────┘
```

### Event Gateway API (port 8080)
- Entry point for all client requests
- Validates event payloads, enforces idempotency via local H2 database
- Rate limiting (50 requests/min per IP via Bucket4j)
- Calls Account Service via synchronous REST with circuit breaker protection
- Async fallback: queues events locally when Account Service is down, retries on recovery
- Propagates OpenTelemetry trace context (W3C `traceparent` + custom `X-Trace-Id`)
- Returns events ordered by `eventTimestamp` (handles out-of-order arrival)

### Account Service (port 8081)
- Internal service managing account state (balances, transaction history)
- Owns its own H2 in-memory database
- Computes balance as: `SUM(CREDITs) - SUM(DEBITs)`
- Idempotent transaction application (duplicate `eventId` ignored)

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.2.5 |
| Database | H2 (in-memory, per service) |
| Resiliency | Resilience4j (Circuit Breaker + Retry with backoff) |
| Rate Limiting | Bucket4j |
| Tracing | Micrometer Tracing + OpenTelemetry bridge |
| Trace Viz | Jaeger (via Docker Compose) |
| Metrics | Micrometer + Prometheus endpoint |
| Logging | Logstash Logback Encoder (JSON structured) |
| Containers | Docker + Docker Compose |
| Testing | JUnit 5 + MockMvc + OkHttp MockWebServer |

## Prerequisites

- **Java 17+** (for local development)
- **Maven 3.9+** (for local development)
- **Docker & Docker Compose** (for containerized execution)

## Setup & Run

### Option 1: Docker Compose (Recommended)

```bash
docker-compose up --build
```

This starts:
- Event Gateway on `http://localhost:8080`
- Account Service on `http://localhost:8081`
- Jaeger UI on `http://localhost:16686`

### Option 2: Run Locally

Terminal 1 - Account Service:
```bash
cd account-service
mvn spring-boot:run
```

Terminal 2 - Event Gateway:
```bash
cd event-gateway
mvn spring-boot:run
```

## Running Tests

```bash
# Account Service tests (6 tests)
cd account-service
mvn test

# Event Gateway tests (14 tests: 10 core + 4 contract)
cd event-gateway
mvn test
```

## API Endpoints

### Event Gateway (port 8080)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/events` | Submit a transaction event |
| GET | `/events/{id}` | Retrieve a single event by ID |
| GET | `/events?account={accountId}` | List events for an account (chronological) |
| GET | `/accounts/{accountId}/balance` | Get balance (proxied to Account Service) |
| GET | `/health` | Health check with DB diagnostics |

### Account Service (port 8081)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/accounts/{accountId}/transactions` | Apply a transaction |
| GET | `/accounts/{accountId}/balance` | Get current balance |
| GET | `/accounts/{accountId}` | Get account details + recent transactions |
| GET | `/health` | Health check with DB diagnostics |

### Example Request

```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2024-05-15T14:02:11Z",
    "metadata": {"source": "mainframe-batch", "batchId": "B-9042"}
  }'
```

## Resiliency Pattern: Circuit Breaker + Retry with Exponential Backoff

### Why Circuit Breaker?

The **circuit breaker** pattern (Resilience4j) was chosen because:

1. **Fail-fast behavior**: When the Account Service is repeatedly failing, the Gateway stops calling it immediately rather than accumulating timeouts, preserving thread pool resources.

2. **Self-healing**: The circuit breaker automatically transitions from OPEN → HALF_OPEN → CLOSED, allowing the system to recover without manual intervention.

3. **Combined with retry + exponential backoff**: Transient failures (network blips) are handled by retries, while sustained failures trigger the circuit breaker. This provides the best of both patterns.

### Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      accountService:
        slidingWindowSize: 5
        minimumNumberOfCalls: 3
        failureRateThreshold: 50        # Opens after 50% failure rate
        waitDurationInOpenState: 10s     # Waits 10s before trying again
  retry:
    instances:
      accountService:
        maxAttempts: 3
        waitDuration: 500ms
        exponentialBackoffMultiplier: 2  # 500ms, 1s, 2s
```

### Graceful Degradation

| Scenario | Behavior |
|----------|----------|
| Account Service down, POST /events | Returns 503 + event queued for async retry |
| Account Service down, GET /events | Works normally (local data only) |
| Account Service down, GET balance | Returns 503 with clear error |
| Circuit breaker open | Immediate 503 without waiting for timeout |

## Observability

### Structured Logging
All logs are JSON-formatted with trace ID, timestamp, level, and service name (via Logstash Logback Encoder).

### Distributed Tracing
- Trace context propagated via W3C `traceparent` header and custom `X-Trace-Id`
- Viewable in Jaeger UI at `http://localhost:16686`

### Health Check Endpoints
Both services expose `/health` with database connectivity diagnostics:
```json
{
  "status": "healthy",
  "service": "event-gateway",
  "database": { "status": "connected", "url": "jdbc:h2:mem:gatewaydb" },
  "pendingEvents": 0
}
```

### Metrics
- Prometheus endpoint at `/actuator/prometheus`
- Includes HTTP request metrics, JVM metrics, and Resilience4j circuit breaker state

## Rate Limiting

The Gateway implements per-IP rate limiting using Bucket4j:
- **Limit**: 50 requests per minute per client IP
- **Response on exceed**: HTTP 429 with `{"error": "Rate limit exceeded. Try again later."}`
- **Implementation**: `RateLimitFilter` using token bucket algorithm

## Async Fallback Queue

When the Account Service is unavailable, events are **not lost**:

1. The event is saved to a local `pending_events` table in the Gateway's H2 database
2. A `@Scheduled` processor (`PendingEventProcessor`) runs every 15 seconds
3. It retries pending events against the Account Service
4. On success: event is stored in the main events table and removed from the queue
5. On failure: retry count is incremented (max 5 retries)
6. The 503 response to the client includes `"queued": true` indicating the event will be processed later

## Contract Tests

Contract tests (`AccountServiceContractTest`) verify the API contract between Gateway and Account Service:

1. **Request format**: Gateway sends `POST /accounts/{accountId}/transactions` with correct body fields
2. **Trace headers**: `X-Trace-Id` and `traceparent` headers are propagated
3. **Idempotency**: Duplicate events don't trigger additional Account Service calls
4. **Error handling**: Account Service 500 → Gateway returns 503 to client

## Test Coverage

| Test Class | Tests | What's Covered |
|-----------|-------|----------------|
| `AccountServiceApplicationTests` | 6 | Transaction apply, idempotency, balance, health |
| `EventGatewayApplicationTests` | 10 | Idempotency, validation, out-of-order, circuit breaker, trace propagation, graceful degradation |
| `AccountServiceContractTest` | 4 | API contract verification between services |

## Design Decisions

1. **Separate H2 databases**: Each service has its own in-memory database — no shared state.
2. **Idempotency at both layers**: Gateway checks `eventId` before calling Account Service; Account Service also checks to handle edge cases.
3. **Out-of-order handling**: Events stored with `eventTimestamp` and queries sort by it, not by arrival time.
4. **Balance computation via SQL**: Using a single aggregate query for correctness regardless of insertion order.
5. **RestTemplate with interceptor**: Injects trace headers on every outgoing request automatically.
6. **Async fallback over sync-only**: Events are queued locally rather than rejected outright, improving reliability without requiring external message brokers.
7. **Rate limiting per IP**: Protects the Gateway from abuse while allowing legitimate traffic from multiple clients.
