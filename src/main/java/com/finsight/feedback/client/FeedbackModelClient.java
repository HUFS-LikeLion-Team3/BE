package com.finsight.feedback.client;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.service.FeedbackGenerationInput;

import java.util.List;

public interface FeedbackModelClient {

    Result generate(
            AiFeedback.FeedbackStage stage,
            FeedbackGenerationInput input
    );

    record Result(
            String modelVersion,
            String flowSummary,
            boolean insufficientEvidence,
            List<Item> items
    ) {
    }

    record Item(
            String sessionTargetId,
            String type,
            String content,
            List<String> variablesToCheck,
            int sortOrder,
            List<String> referenceChunkIds
    ) {
    }
}