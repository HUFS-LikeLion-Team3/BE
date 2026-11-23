package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.repository.AiRagJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scan the durable ai_rag_jobs rows, including those left after BE restart.
 * Only status/result/delivery endpoints are used; no model submission/retry.
 * In-memory pacing is an optimization, NOT the source of truth.
 */
@Component
@ConditionalOnProperty(prefix = "finsight.ai.rag-polling", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class AiRagPollingScheduler {
    private final AiRagJobRepository repository;
    private final AiRagDeliveryCoordinator coordinator;
    private final Map<UUID, Long> dueAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> failures = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${finsight.ai.rag-polling.interval-ms:5000}",
            initialDelayString = "${finsight.ai.rag-polling.initial-delay-ms:5000}")
    public void poll() {
        List<UUID> candidates = repository.findDeliveryCandidates(List.of(
                AiFeedback.FeedbackStatus.generating, AiFeedback.FeedbackStatus.ready));
        long now = System.currentTimeMillis();
        for (UUID id : candidates) {
            if (now < dueAt.getOrDefault(id, 0L)) {
                continue;
            }
            try {
                AiRagDeliveryCoordinator.Outcome outcome = coordinator.processOne(id);
                failures.remove(id);
                long delay = switch (outcome) {
                    case REVIEW, BLOCKED -> 30_000L;
                    case WAITING -> 5_000L;
                    case RECONCILIATION, FAILED, STORED -> 60_000L;
                };
                dueAt.put(id, System.currentTimeMillis() + delay);
            } catch (RuntimeException ex) {
                int attempts = failures.merge(id, 1, (a, b) -> Math.min(a + b, 5));
                long delay = Math.min(60_000L, 5_000L << Math.min(attempts - 1, 4));
                dueAt.put(id, System.currentTimeMillis() + delay);
                // Do not log raw approved response, bearer token or student input.
                log.warn("AI/RAG poll postponed. feedbackId={}, errorType={}",
                        id, ex.getClass().getSimpleName());
            }
        }
        // The next DB scan is authoritative; stale cache entries are not persistent.
        dueAt.keySet().retainAll(candidates);
        failures.keySet().retainAll(candidates);
    }
}
