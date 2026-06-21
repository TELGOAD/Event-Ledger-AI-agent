package com.eventledger.account.controller;

import com.eventledger.account.model.TransactionRequest;
import com.eventledger.account.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@RestController
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);
    private final AccountService accountService;
    private final DataSource dataSource;

    public AccountController(AccountService accountService, DataSource dataSource) {
        this.accountService = accountService;
        this.dataSource = dataSource;
    }

    @PostMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<Map<String, Object>> applyTransaction(
            @PathVariable String accountId,
            @RequestBody TransactionRequest request) {
        log.info("Applying transaction: accountId={}, eventId={}", accountId, request.eventId());
        Map<String, Object> result = accountService.applyTransaction(accountId, request);
        HttpStatus status = "already_applied".equals(result.get("status")) ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String accountId) {
        return ResponseEntity.ok(accountService.getBalance(accountId));
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<Map<String, Object>> getAccount(@PathVariable String accountId) {
        return ResponseEntity.ok(accountService.getAccount(accountId));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "healthy");
        response.put("service", "account-service");

        try (Connection conn = dataSource.getConnection()) {
            response.put("database", Map.of("status", "connected", "url", conn.getMetaData().getURL()));
        } catch (Exception e) {
            response.put("database", Map.of("status", "disconnected", "error", e.getMessage()));
        }
        return ResponseEntity.ok(response);
    }
}
