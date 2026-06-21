package com.eventledger.account;

import com.eventledger.account.model.TransactionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AccountServiceApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthCheck() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("healthy"))
            .andExpect(jsonPath("$.service").value("account-service"));
    }

    @Test
    void applyTransaction_success() throws Exception {
        TransactionRequest request = new TransactionRequest("evt-100", "CREDIT", new BigDecimal("100.00"), "USD", "2024-01-15T10:00:00Z");

        mockMvc.perform(post("/accounts/acct-1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("applied"))
            .andExpect(jsonPath("$.eventId").value("evt-100"));
    }

    @Test
    void applyTransaction_idempotent() throws Exception {
        TransactionRequest request = new TransactionRequest("evt-200", "CREDIT", new BigDecimal("50.00"), "USD", "2024-01-15T11:00:00Z");
        String json = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/accounts/acct-2/transactions")
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isCreated());

        // Second submission - should be idempotent
        mockMvc.perform(post("/accounts/acct-2/transactions")
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("already_applied"));
    }

    @Test
    void getBalance_correctCalculation() throws Exception {
        // Credit 200
        mockMvc.perform(post("/accounts/acct-3/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new TransactionRequest("evt-300", "CREDIT", new BigDecimal("200.00"), "USD", "2024-01-15T10:00:00Z"))))
            .andExpect(status().isCreated());

        // Debit 50
        mockMvc.perform(post("/accounts/acct-3/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new TransactionRequest("evt-301", "DEBIT", new BigDecimal("50.00"), "USD", "2024-01-15T11:00:00Z"))))
            .andExpect(status().isCreated());

        // Balance should be 150
        mockMvc.perform(get("/accounts/acct-3/balance"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(150.00));
    }

    @Test
    void getAccount_returnsTransactions() throws Exception {
        mockMvc.perform(post("/accounts/acct-4/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new TransactionRequest("evt-400", "CREDIT", new BigDecimal("75.00"), "USD", "2024-01-15T09:00:00Z"))))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/acct-4"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value("acct-4"))
            .andExpect(jsonPath("$.transactions[0].eventId").value("evt-400"));
    }

    @Test
    void applyTransaction_withTraceHeader() throws Exception {
        TransactionRequest request = new TransactionRequest("evt-500", "CREDIT", new BigDecimal("25.00"), "USD", "2024-01-15T12:00:00Z");

        mockMvc.perform(post("/accounts/acct-5/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Trace-Id", "test-trace-123")
                .header("traceparent", "00-test-trace-123-span-456-01")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());
    }
}
