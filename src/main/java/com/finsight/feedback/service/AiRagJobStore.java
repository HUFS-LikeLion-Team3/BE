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