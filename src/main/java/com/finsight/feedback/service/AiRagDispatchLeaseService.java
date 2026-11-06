package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Database-backed dispatch ownership. Transactions commit before HTTP is sent;
 * never hold a MySQL row lock across a remote network request.
 */
@Service
@RequiredArgsConstructor
public class AiRagDispatchLeaseService {

    // AI client: GET timeout 30s + optional POST timeout 30s.
    // Leave margin for response processing, but recover after process failure.
    static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    private final AiFeedbackRepository feedbackRepository;
    private final AiRagJobRepository jobRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID tryClaim(UUID feedbackId) {
        // Always lock feedback first: same order as ApprovedFeedbackPersistenceService.
        AiFeedback feedback = feedbackRepository.findByIdForUpdate(feedbackId)
                .orElseThrow(() -> new IllegalStateException("feedback_missing"));
        if (feedback.getStatus() != AiFeedback.FeedbackStatus.pending
                && feedback.getStatus() != AiFeedback.FeedbackStatus.generating) {
            return null;
        }
        AiRagJob job = jobRepository.findByIdForUpdate(feedbackId)
                .orElseThrow(() -> new IllegalStateException("frozen_job_missing"));
        UUID owner = UUID.randomUUID();
        if (!job.claimDispatch(owner, OffsetDateTime.now(), LEASE_DURATION)) {
            return null;
        }
        if (feedback.getStatus() == AiFeedback.FeedbackStatus.pending) {
            feedback.markGenerating();
        }
        return owner;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID feedbackId, UUID owner) {
        if (owner == null) {
            return;
        }
        jobRepository.findByIdForUpdate(feedbackId)
                .ifPresent(job -> job.releaseDispatch(owner));
    }
}
