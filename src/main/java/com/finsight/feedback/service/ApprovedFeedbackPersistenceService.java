package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackItem;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackItemRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The ONLY write path for an already approved/captured RAG result. Source
 * verification, item/reference inserts, and ready transition share one DB tx.
 * A failed citation lookup cannot leave a half-visible ready feedback.
 */
@Service
@RequiredArgsConstructor
public class ApprovedFeedbackPersistenceService {

    private final AiFeedbackRepository feedbackRepository;
    private final AiRagJobRepository jobRepository;
    private final AiFeedbackItemRepository itemRepository;
    private final ApprovedCitationWriter citationWriter;
    private final ApprovedRagResultParser parser;
    private final ApprovedSourceIntegrityVerifier sourceIntegrity;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean persistCaptured(UUID feedbackId) {
        AiFeedback feedback = feedbackRepository.findByIdForUpdate(feedbackId)
                .orElseThrow(() -> new IllegalStateException("approved_feedback_missing"));
        AiRagJob job = jobRepository.findByIdForUpdate(feedbackId)
                .orElseThrow(() -> new IllegalStateException("approved_rag_job_missing"));
        if (feedback.getStatus() == AiFeedback.FeedbackStatus.ready) {
            return false; // Repeated delivery/resume must not create new refs/items.
        }
        if (feedback.getStatus() != AiFeedback.FeedbackStatus.generating) {
            throw new IllegalStateException("approved_feedback_not_generating");
        }

        ApprovedRagResultParser.Parsed approved = parser.parse(job, feedback);
        // Re-hash all chunks of every referenced document as stored in MySQL.
        // A missing/modified source blocks the entire write; nothing becomes ready.
        sourceIntegrity.verify(approved.citations());
        int order = 0;
        List<ApprovedCitationWriter.ItemLinks> links = new ArrayList<>();
        for (ApprovedRagResultParser.Item item : approved.items()) {
            // DB sort_order is unique *across the feedback*, whereas AI sortOrder
            // is scoped to session / target. The parser preserves scoped order.
            AiFeedbackItem saved = itemRepository.save(AiFeedbackItem.create(
                    feedbackId, item.sessionTargetId(), item.type(), item.content(),
                    null, ++order));
            links.add(new ApprovedCitationWriter.ItemLinks(saved.getId(), item.referenceIds()));
        }
        // Mandatory transaction: validates real source/chunk ownership and every
        // item link before persisting all references and reference links.
        citationWriter.write(feedbackId, approved.citations(), links);
        feedback.markApprovedReady(approved.modelVersion(), approved.promptVersion(),
                approved.policyVersion(), approved.flowSummary(),
                approved.insufficientEvidence(), approved.generatedAt());
        return true;
    }
}
