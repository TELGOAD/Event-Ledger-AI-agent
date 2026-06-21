package com.eventledger.gateway.contract;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests verifying the API contract between Event Gateway and Account Service.
 * Validates request format, required headers, and response handling.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountServiceContractTest {

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
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    @Test
    @Order(1)
    @DisplayName("Contract: Gateway sends POST /accounts/{accountId}/transactions with correct body")
    void contract_requestFormat() throws Exception {
        mockAccountService.enqueue(new MockResponse()
            .setBody("{\"status\":\"applied\",\"eventId\":\"evt-c1\"}")
            .setHeader("Content-Type", "application/json"));

        String json = objectMapper.writeValueAsString(Map.of(
            "eventId", "evt-c1", "accountId", "acct-c1", "type", "CREDIT",
            "amount", 100.00, "currency", "USD", "eventTimestamp", "2024-01-01T10:00:00Z"
        ));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isCreated());

        RecordedRequest recorded = mockAccountService.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/accounts/acct-c1/transactions");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = objectMapper.readValue(recorded.getBody().readUtf8(), Map.class);
        assertThat(body).containsKeys("eventId", "type", "amount", "currency", "eventTimestamp");
        assertThat(body.get("eventId")).isEqualTo("evt-c1");
        assertThat(body.get("type")).isEqualTo("CREDIT");
    }

    @Test
    @Order(2)
    @DisplayName("Contract: Trace headers (X-Trace-Id, traceparent) are propagated")
    void contract_traceHeaders() throws Exception {
        mockAccountService.enqueue(new MockResponse()
            .setBody("{\"status\":\"applied\",\"eventId\":\"evt-c2\"}")
            .setHeader("Content-Type", "application/json"));

        String json = objectMapper.writeValueAsString(Map.of(
            "eventId", "evt-c2", "accountId", "acct-c2", "type", "DEBIT",
            "amount", 50.00, "currency", "USD", "eventTimestamp", "2024-01-01T11:00:00Z"
        ));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isCreated());

        RecordedRequest recorded = mockAccountService.takeRequest();
        assertThat(recorded.getHeader("X-Trace-Id")).isNotNull().isNotEmpty();
        assertThat(recorded.getHeader("traceparent")).isNotNull().isNotEmpty();
        assertThat(recorded.getHeader("Content-Type")).contains("application/json");
    }

    @Test
    @Order(4)
    @DisplayName("Contract: Gateway returns 503 when Account Service returns 500")
    void contract_errorHandling() throws Exception {
        mockAccountService.enqueue(new MockResponse().setResponseCode(500));

        String json = objectMapper.writeValueAsString(Map.of(
            "eventId", "evt-c3", "accountId", "acct-c3", "type", "CREDIT",
            "amount", 10.00, "currency", "USD", "eventTimestamp", "2024-01-01T12:00:00Z"
        ));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    @Order(3)
    @DisplayName("Contract: Idempotency - duplicate returns 200 without calling Account Service again")
    void contract_idempotency() throws Exception {
        mockAccountService.enqueue(new MockResponse()
            .setBody("{\"status\":\"applied\",\"eventId\":\"evt-c4\"}")
            .setHeader("Content-Type", "application/json"));

        String json = objectMapper.writeValueAsString(Map.of(
            "eventId", "evt-c4", "accountId", "acct-c4", "type", "CREDIT",
            "amount", 75.00, "currency", "USD", "eventTimestamp", "2024-01-01T13:00:00Z"
        ));

        int requestsBefore = mockAccountService.getRequestCount();

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isCreated());

        // Second call - should NOT call Account Service
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk());

        // Only 1 new request should have been sent to mock server
        int requestsAfter = mockAccountService.getRequestCount();
        assertThat(requestsAfter - requestsBefore).isEqualTo(1);
    }
}
