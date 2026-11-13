
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        prefix = "finsight.ai.rag-polling",
        name = "enabled",
        havingValue = "true"
)
public class AiRagPendingRecoveryScheduler {

    private static final int PAGE_SIZE = 200;
    private static final long RETRY_DELAY_MS = 30_000L;

    private final EntityManager entityManager;
    private final AiFeedbackRepository feedbackRepository;
    private final AiRagJobRepository jobRepository;
    private final FeedbackGenerationService generationService;

    private final Map<UUID, Long> nextAllowedAt =
            new ConcurrentHashMap<>();

    private int nextOffset = 0;

    @Scheduled(
            fixedDelayString =
                    "${finsight.ai.rag-polling.interval-ms:5000}",
            initialDelayString =
                    "${finsight.ai.rag-polling.initial-delay-ms:5000}"
    )
    public synchronized void recover() {

        List<UUID> candidates = findCandidates(nextOffset);

        // Reset the scan when the end is reached.
        if (candidates.isEmpty() && nextOffset != 0) {
            nextOffset = 0;
            candidates = findCandidates(0);
        }

        if (candidates.size() == PAGE_SIZE) {
            nextOffset += PAGE_SIZE;
        } else {
            nextOffset = 0;
        }

        long now = System.currentTimeMillis();

        for (UUID feedbackId : candidates) {

            if (now < nextAllowedAt.getOrDefault(
                    feedbackId, 0L)) {
                continue;
            }

            try {
                recoverOne(feedbackId);

            } catch (RuntimeException ex) {
                log.warn(
                        "AI/RAG pending recovery deferred. "
                                + "feedbackId={}, errorType={}",
                        feedbackId,
                        ex.getClass().getSimpleName(),
                        ex
                );

            } finally {
                nextAllowedAt.put(
                        feedbackId,
                        System.currentTimeMillis()
                                + RETRY_DELAY_MS
                );
            }
        }

        // Remove expired entries to avoid retaining
        // every scanned feedback ID indefinitely.
        long expireBefore =
                System.currentTimeMillis() - RETRY_DELAY_MS;

        nextAllowedAt.entrySet().removeIf(
                entry -> entry.getValue() < expireBefore
        );
    }

    private List<UUID> findCandidates(int offset) {

        return entityManager.createQuery(
                        """
                        SELECT f.id
                        FROM AiFeedback f
                        WHERE f.status IN :statuses
                          AND f.version = :version
                          AND (
                            NOT EXISTS (
                              SELECT j.feedbackId
                              FROM AiRagJob j
                              WHERE j.feedbackId = f.id
                            )
                            OR EXISTS (
                              SELECT j.feedbackId
                              FROM AiRagJob j
                              WHERE j.feedbackId = f.id
                                AND j.jobId IS NULL
                                AND j.state NOT IN (
                                  'stored',
                                  'blocked',
                                  'failed',
                                  'reconciliation_required'
                                )
                            )
                          )
                        ORDER BY f.createdAt ASC, f.id ASC
                        """,
                        UUID.class
                )
                .setParameter(
                        "statuses",
                        List.of(
                                AiFeedback.FeedbackStatus.pending,
                                AiFeedback.FeedbackStatus.generating
                        )
                )
                .setParameter("version", 1)
                .setFirstResult(offset)
                .setMaxResults(PAGE_SIZE)
                .getResultList();
    }

    private void recoverOne(UUID feedbackId) {

        AiFeedback feedback = feedbackRepository
                .findById(feedbackId)
                .orElse(null);

        if (feedback == null) {
            return;
        }

        if (feedback.getStatus()
                != AiFeedback.FeedbackStatus.pending
                && feedback.getStatus()
                != AiFeedback.FeedbackStatus.generating) {
            return;
        }

        AiRagJob job = jobRepository
                .findById(feedbackId)
                .orElse(null);

        // A pending intent alone is NOT a frozen input.
        // Rebuilding from the current DB would violate
        // the immutable-submission requirement.
        if (job == null) {
            log.error(
                    "AI/RAG recovery blocked: frozen_job_missing. "
                            + "feedbackId={}, sessionId={}, stage={}. "
                            + "Original input must not be rebuilt.",
                    feedbackId,
                    feedback.getLearningSessionId(),
                    feedback.getFeedbackStage()
            );
            return;
        }

        // Accepted jobs belong to the delivery poller.
        if (job.getJobId() != null) {
            return;
        }

        // Never automatically restart a terminal,
        // blocked or reconciliation-required job.
        String state = job.getState();

        if ("stored".equals(state)
                || "blocked".equals(state)
                || "failed".equals(state)
                || "reconciliation_required".equals(state)) {
            return;
        }

        UUID sessionId = feedback.getLearningSessionId();

        if (feedback.getFeedbackStage()
                == AiFeedback.FeedbackStage.INITIAL) {

            if (job.getRequestJson() == null) {
                log.error(
                        "AI/RAG recovery blocked: "
                                + "initial_frozen_request_missing. "
                                + "feedbackId={}",
                        feedbackId
                );
                return;
            }

            // Existing job: generation service must
            // reuse the exact stored request JSON.
            generationService.generateInitial(
                    sessionId,
                    feedback.getInputCutoffAt()
            );

            return;
        }

        if (feedback.getFeedbackStage()
                == AiFeedback.FeedbackStage.FINAL) {

            // FINAL may have requestJson=null while
            // waiting for the INITIAL job ID.
            // resumeFinal uses the saved snapshot.
            generationService.resumeFinal(sessionId);
        }
    }
}
