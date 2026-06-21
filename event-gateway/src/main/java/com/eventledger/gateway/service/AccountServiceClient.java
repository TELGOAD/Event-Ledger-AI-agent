package com.eventledger.gateway.service;

import com.eventledger.gateway.model.EventRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    private final RestTemplate restTemplate;
    private final String accountServiceUrl;

    public AccountServiceClient(RestTemplate restTemplate,
                                @Value("${account-service.url}") String accountServiceUrl) {
        this.restTemplate = restTemplate;
        this.accountServiceUrl = accountServiceUrl;
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "applyTransactionFallback")
    @Retry(name = "accountService")
    public Map<String, Object> applyTransaction(String accountId, EventRequest event) {
        String url = accountServiceUrl + "/accounts/" + accountId + "/transactions";
        Map<String, Object> body = Map.of(
            "eventId", event.eventId(),
            "type", event.type(),
            "amount", event.amount(),
            "currency", event.currency(),
            "eventTimestamp", event.eventTimestamp()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("Calling Account Service: accountId={}, eventId={}", accountId, event.eventId());
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
        return response;
    }

    @SuppressWarnings("unused")
    private Map<String, Object> applyTransactionFallback(String accountId, EventRequest event, Throwable t) {
        log.error("Account Service unavailable (circuit breaker fallback): accountId={}, error={}", accountId, t.getMessage());
        throw new AccountServiceUnavailableException("Account Service is unavailable: " + t.getMessage());
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "getBalanceFallback")
    public Map<String, Object> getBalance(String accountId) {
        String url = accountServiceUrl + "/accounts/" + accountId + "/balance";
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);
        return response;
    }

    @SuppressWarnings("unused")
    private Map<String, Object> getBalanceFallback(String accountId, Throwable t) {
        log.error("Account Service unavailable for balance query: accountId={}", accountId);
        throw new AccountServiceUnavailableException("Account Service is unavailable for balance query");
    }

    public static class AccountServiceUnavailableException extends RuntimeException {
        public AccountServiceUnavailableException(String message) {
            super(message);
        }
    }
}
