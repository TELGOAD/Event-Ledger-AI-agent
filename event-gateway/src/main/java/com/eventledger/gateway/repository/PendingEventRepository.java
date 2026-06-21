package com.eventledger.gateway.repository;

import com.eventledger.gateway.model.PendingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PendingEventRepository extends JpaRepository<PendingEvent, Long> {
    List<PendingEvent> findByRetryCountLessThanOrderByCreatedAtAsc(int maxRetries);
}
