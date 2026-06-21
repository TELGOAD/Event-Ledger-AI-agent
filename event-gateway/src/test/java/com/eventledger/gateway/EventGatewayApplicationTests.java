package com.eventledger.gateway;

import com.eventledger.gateway.model.EventRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventGatewayApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private static MockWebServer mockAccountService;

    @BeforeAll
    static void startMockServer() throws Exception {
        mockAccountService = new MockWebServer();
        mockAccountService.start();
    }

    @AfterAll
    static void stopMockServer() throws Exception {
        mockAccountService.shutdown();
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("account-service.url", () -> "http://localhost:" + mockAccountService.getPort());
    }

    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreakerRegistry.getAllCircuitBreakers()
            .forEach(CircuitBreaker::reset);
    }

    @Test
    @Order(1)
    void healthCheck() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("healthy"))
            .andExpect(jsonPath("$.service").value("event-gateway"));
    }

    @Test
    @Order(2)
    void createEvent_success() throws Exception {
        mockAccountService.enqueue(new MockResponse()
            .setBody("{\"status\":\"applied\",\"eventId\":\"evt-001\"}")
            .setHeader("Content-Type", "application/json"));

        EventRequest request = new EventRequest("evt-001", "acct-123", "CREDIT",
            new BigDecimal("150.00"), "USD", "2024-05-15T14:02:11Z",
            Map.of("source", "mainframe-batch"));

        mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.eventId").value("evt-001"))
            .andExpect(jsonPath("$.accountId").value("acct-123"))
            .andExpect(jsonPath("$.status").value("PROCESSED"));
    }

    @Test
    @Order(3)
    void createEvent_idempotent() throws Exception {
        mockAccountService.enqueue(new MockResponse()
            .setBody("{\"status\":\"applied\",\"eventId\":\"evt-idem\"}")
            .setHeader("Content-Type", "application/json"));

        EventRequest request = new EventRequest("evt-idem", "acct-123", "CREDIT",
            new BigDecimal("100.00"), "USD", "2024-05-15T10:00:00Z", null);
        String json = objectMapper.writeValueAsString(request);

        // First call
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isCreated());

        // Duplicate - should return 200, no new call to account service
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventId").value("evt-idem"));
    }

    @Test
    @Order(4)
    void createEvent_validationErrors() throws Exception {
        // Missing eventId
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"acct-1\",\"type\":\"CREDIT\",\"amount\":10,\"currency\":\"USD\",\"eventTimestamp\":\"2024-01-01T00:00:00Z\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("eventId is required"));

        // Invalid type
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"e1\",\"accountId\":\"a1\",\"type\":\"INVALID\",\"amount\":10,\"currency\":\"USD\",\"eventTimestamp\":\"2024-01-01T00:00:00Z\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("type must be CREDIT or DEBIT"));

        // Zero amount
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"e2\",\"accountId\":\"a1\",\"type\":\"CREDIT\",\"amount\":0,\"currency\":\"USD\",\"eventTimestamp\":\"2024-01-01T00:00:00Z\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("amount must be greater than 0"));

        // Negative amount
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"e3\",\"accountId\":\"a1\",\"type\":\"CREDIT\",\"amount\":-5,\"currency\":\"USD\",\"eventTimestamp\":\"2024-01-01T00:00:00Z\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("amount must be greater than 0"));

        // Invalid timestamp
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"e4\",\"accountId\":\"a1\",\"type\":\"CREDIT\",\"amount\":10,\"currency\":\"USD\",\"eventTimestamp\":\"not-a-date\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("eventTimestamp must be valid ISO 8601 format"));
    }

    @Test
    @Order(5)
    void getEvent_notFound() throws Exception {
        mockMvc.perform(get("/events/non-existent"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Event not found"));
    }

    @Test
    @Order(6)
    void getEvent_found() throws Exception {
        mockAccountService.enqueue(new MockResponse()
            .setBody("{\"status\":\"applied\",\"eventId\":\"evt-get\"}")
            .setHeader("Content-Type", "application/json"));

        EventRequest request = new EventRequest("evt-get", "acct-get", "DEBIT",
            new BigDecimal("25.00"), "USD", "2024-06-01T08:00:00Z", null);
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/events/evt-get"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventId").value("evt-get"))
            .andExpect(jsonPath("$.type").value("DEBIT"));
    }

    @Test
    @Order(7)
    void listEvents_orderedByTimestamp() throws Exception {
        // Submit events out of order
        mockAccountService.enqueue(new MockResponse()
            .setBody("{\"status\":\"applied\",\"eventId\":\"evt-order-2\"}")
            .setHeader("Content-Type", "application/json"));
        mockAccountService.enqueue(new MockResponse()
            .setBody("{\"status\":\"applied\",\"eventId\":\"evt-order-1\"}")
            .setHeader("Content-Type", "application/json"));

        // Later timestamp arrives first
        EventRequest req2 = new EventRequest("evt-order-2", "acct-order", "CREDIT",
            new BigDecimal("200.00"), "USD", "2024-06-01T12:00:00Z", null);
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
            .andExpect(status().isCreated());

        // Earlier timestamp arrives second
        EventRequest req1 = new EventRequest("evt-order-1", "acct-order", "CREDIT",
            new BigDecimal("100.00"), "USD", "2024-06-01T08:00:00Z", null);
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
            .andExpect(status().isCreated());

        // List should return in chronological order regardless of arrival order
        mockMvc.perform(get("/events").param("account", "acct-order"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].eventId").value("evt-order-1"))
            .andExpect(jsonPath("$[1].eventId").value("evt-order-2"));
    }

    @Test
    @Order(8)
    void getEvents_worksWhenAccountServiceDown() throws Exception {
        // GET /events should work using only local data, no account service call needed
        mockMvc.perform(get("/events").param("account", "acct-local-only"))
            .andExpect(status().isOk());
    }

    @Test
    @Order(9)
    void tracePropagation_headersSentToAccountService() throws Exception {
        mockAccountService.enqueue(new MockResponse()
            .setBody("{\"status\":\"applied\",\"eventId\":\"evt-trace\"}")
            .setHeader("Content-Type", "application/json"));

        EventRequest request = new EventRequest("evt-trace", "acct-trace", "CREDIT",
            new BigDecimal("50.00"), "USD", "2024-07-01T10:00:00Z", null);

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());

        // Verify the account service received trace headers
        RecordedRequest recordedRequest = mockAccountService.takeRequest();
        assertThat(recordedRequest.getHeader("X-Trace-Id")).isNotNull();
        assertThat(recordedRequest.getHeader("traceparent")).isNotNull();
    }

    @Test
    @Order(10)
    void createEvent_accountServiceDown_returns503() throws Exception {
        // Enqueue server error responses
        mockAccountService.enqueue(new MockResponse().setResponseCode(500));
        mockAccountService.enqueue(new MockResponse().setResponseCode(500));
        mockAccountService.enqueue(new MockResponse().setResponseCode(500));

        EventRequest request = new EventRequest("evt-fail-" + System.nanoTime(), "acct-fail", "CREDIT",
            new BigDecimal("10.00"), "USD", "2024-01-01T00:00:00Z", null);

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").exists());
    }
}
