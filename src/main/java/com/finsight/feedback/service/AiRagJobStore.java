
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiRagJob ensure(
            UUID feedbackId,
            UUID sessionId,
            String stage,
            String requestId,
            String packageId,
            String requestJson
    ) {
        var existing = repository.findByIdForUpdate(feedbackId);

        if (existing.isPresent()) {
            AiRagJob job = existing.get();

            if (!job.getSessionId().equals(sessionId)
                    || !job.getStage().equals(stage)
                    || !job.getBeRequestId().equals(requestId)
                    || !job.getPackageId().equals(packageId)) {
                throw new IllegalStateException("request_input_conflict");
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accepted(
            UUID feedbackId,
            UUID jobId,
            String status
    ) {
        AiRagJob job = repository.findByIdForUpdate(feedbackId)
                .orElseThrow();

        if (job.getJobId() != null) {
            if (!job.getJobId().equals(jobId)) {
                throw new IllegalStateException("request_input_conflict");
            }

            // A late duplicate receipt must not reset stored/blocked work.
            return;
        }

        if ("reconciliation_required".equals(job.getState())) {
            throw new IllegalStateException(
                    "ai_job_requires_reconciliation"
            );
        }

        job.accepted(jobId, status);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void uncertain(
            UUID feedbackId,
            String failureCode
    ) {
        AiRagJob job = repository.findByIdForUpdate(feedbackId)
                .orElseThrow();

        if (!"stored".equals(job.getState())) {
            job.uncertain(failureCode);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatus(
            UUID feedbackId,
            String status,
            String ragSnapshotHash
    ) {
        repository.findByIdForUpdate(feedbackId)
                .orElseThrow()
                .updateStatus(status, ragSnapshotHash);
    }

    // #26: Preserve the original approved result in its own transaction.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void captureApprovedResult(
            UUID feedbackId,
            UUID aiJobId,
            String originalJson,
            String resultHash
    ) {
        repository.findByIdForUpdate(feedbackId)
                .orElseThrow(() ->
                        new IllegalStateException("ai_rag_job_missing")
                )
                .captureApprovedResult(
                        aiJobId,
                        originalJson,
                        resultHash
                );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void blocked(
            UUID feedbackId,
            String reason
    ) {
        AiRagJob job = repository.findByIdForUpdate(feedbackId)
                .orElseThrow();

        job.markDeliveryBlocked(reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stored(
            UUID feedbackId,
            UUID aiJobId,
            String resultHash
    ) {
        AiRagJob job = repository.findByIdForUpdate(feedbackId)
                .orElseThrow();

        job.markDeliveryStored(aiJobId, resultHash);
    }
}
