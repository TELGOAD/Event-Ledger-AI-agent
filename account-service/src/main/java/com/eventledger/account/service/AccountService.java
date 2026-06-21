package com.eventledger.account.service;

import com.eventledger.account.model.Transaction;
import com.eventledger.account.model.TransactionRequest;
import com.eventledger.account.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private final TransactionRepository repository;

    public AccountService(TransactionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Map<String, Object> applyTransaction(String accountId, TransactionRequest request) {
        Optional<Transaction> existing = repository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            log.info("Duplicate transaction ignored: eventId={}", request.eventId());
            return Map.of("status", "already_applied", "eventId", request.eventId());
        }

        Transaction txn = new Transaction(
            request.eventId(),
            accountId,
            request.type(),
            request.amount(),
            request.currency(),
            Instant.parse(request.eventTimestamp())
        );
        repository.save(txn);
        log.info("Transaction applied: eventId={}, accountId={}, type={}, amount={}",
            request.eventId(), accountId, request.type(), request.amount());

        return Map.of("status", "applied", "eventId", request.eventId());
    }

    public Map<String, Object> getBalance(String accountId) {
        BigDecimal balance = repository.calculateBalance(accountId);
        return Map.of("accountId", accountId, "balance", balance, "currency", "USD");
    }

    public Map<String, Object> getAccount(String accountId) {
        BigDecimal balance = repository.calculateBalance(accountId);
        List<Transaction> transactions = repository.findByAccountIdOrderByEventTimestampDesc(accountId);

        List<Map<String, Object>> txnList = transactions.stream().map(t -> Map.<String, Object>of(
            "eventId", t.getEventId(),
            "type", t.getType(),
            "amount", t.getAmount(),
            "currency", t.getCurrency(),
            "eventTimestamp", t.getEventTimestamp().toString()
        )).toList();

        return Map.of("accountId", accountId, "balance", balance, "transactions", txnList);
    }
}
