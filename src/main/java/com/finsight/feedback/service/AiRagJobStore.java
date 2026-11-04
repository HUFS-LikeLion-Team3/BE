package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiRagJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiRagJobStore {

    private final AiRagJobRepository repository;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public AiRagJob ensure(
            UUID feedbackId,
            UUID sessionId,
            String stage,
            String requestId,
            String packageId,
            String requestJson
    ) {
        var existing = repository.findByIdForUpdate(
                feedbackId
        );

        if (existing.isPresent()) {
            AiRagJob job = existing.get();

            if (!job.getSessionId().equals(sessionId)
                    || !job.getStage().equals(stage)
                    || !job.getBeRequestId().equals(requestId)
                    || !job.getPackageId().equals(packageId)) {
                throw new IllegalStateException(
                        "request_input_conflict"
                );
            }

            if (requestJson != null) {
                job.freeze(requestJson);
            }

            return job;
        }

        return repository.save(
                AiRagJob.create(
                        feedbackId,
                        sessionId,
                        stage,
                        requestId,
                        packageId,
                        requestJson
                )
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public void accepted(
            UUID feedbackId,
            UUID jobId,
            String status
    ) {
        repository.findByIdForUpdate(feedbackId)
                .orElseThrow()
                .accepted(jobId, status);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public void uncertain(
            UUID feedbackId,
            String failureCode
    ) {
        repository.findByIdForUpdate(feedbackId)
                .orElseThrow()
                .uncertain(failureCode);
    }

    /**
     * Capture in its own committed transaction BEFORE any citation/source writes.
     * The existing ai_rag_jobs row and AI job identity are locked and verified.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void captureApprovedResult(
            UUID feedbackId,
            UUID aiJobId,
            String originalJson,
            String resultHash
    ) {
        repository.findByIdForUpdate(feedbackId)
                .orElseThrow(() -> new IllegalStateException("ai_rag_job_missing"))
                .captureApprovedResult(aiJobId, originalJson, resultHash);
    }

    /** Persist local storage block even if the AI delivery HTTP call fails. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void blocked(UUID feedbackId, String reason) {
        AiRagJob job = repository.findByIdForUpdate(feedbackId).orElseThrow();
        job.markDeliveryBlocked(reason);
    }

    /** Mark stored only AFTER AI has acknowledged the delivery POST. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stored(UUID feedbackId, UUID aiJobId, String resultHash) {
        AiRagJob job = repository.findByIdForUpdate(feedbackId).orElseThrow();
        job.markDeliveryStored(aiJobId, resultHash);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public void updateStatus(
            UUID feedbackId,
            String status,
            String ragSnapshotHash
    ) {
        repository.findByIdForUpdate(feedbackId)
                .orElseThrow()
                .updateStatus(status, ragSnapshotHash);
    }
}