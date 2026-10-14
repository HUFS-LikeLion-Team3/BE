package com.finsight.feedback.service;

import com.finsight.feedback.client.FeedbackModelClient;
import com.finsight.feedback.entity.AiFeedback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackGenerationService {

    private static final int P0_VERSION = 1;

    private final FeedbackGenerationInputBuilder inputBuilder;
    private final FeedbackGenerationPersistenceService persistenceService;
    private final FeedbackModelClient modelClient;

    @Value("${finsight.ai.prompt-version:feedback-p0-v1}")
    private String promptVersion;

    @Value("${finsight.ai.policy-version:feedback-policy-p0-v1}")
    private String policyVersion;

    public void generateInitial(
            UUID sessionId,
            OffsetDateTime submittedAt
    ) {
        generate(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL,
                "feedback:initial:"
                        + sessionId
                        + ":v1",
                submittedAt
        );
    }

    public void generateFinal(
            UUID sessionId,
            UUID reflectionSubmissionId,
            OffsetDateTime completedAt
    ) {
        OffsetDateTime cutoff =
                inputBuilder.resolveFinalCutoff(
                        sessionId,
                        completedAt
                );

        generate(
                sessionId,
                AiFeedback.FeedbackStage.FINAL,
                "feedback:final:"
                        + sessionId
                        + ":"
                        + reflectionSubmissionId,
                cutoff
        );
    }

    private void generate(
            UUID sessionId,
            AiFeedback.FeedbackStage stage,
            String requestId,
            OffsetDateTime cutoff
    ) {
        UUID feedbackId = null;

        try {
            feedbackId =
                    persistenceService.prepare(
                            sessionId,
                            stage,
                            P0_VERSION,
                            requestId,
                            promptVersion,
                            policyVersion,
                            cutoff
                    );

            FeedbackGenerationInput input =
                    stage
                            == AiFeedback.FeedbackStage.INITIAL
                            ? inputBuilder.buildInitial(
                            sessionId,
                            cutoff
                    )
                            : inputBuilder.buildFinal(
                            sessionId,
                            cutoff
                    );

            persistenceService.saveSnapshot(
                    feedbackId,
                    input
            );

            if (!persistenceService.claim(
                    feedbackId
            )) {
                return;
            }

            FeedbackModelClient.Result result =
                    modelClient.generate(
                            stage,
                            input
                    );

            persistenceService.complete(
                    feedbackId,
                    stage,
                    input,
                    result
            );
        } catch (Exception e) {
            log.error(
                    "AI feedback generation failed. sessionId={}, stage={}",
                    sessionId,
                    stage,
                    e
            );

            if (feedbackId != null) {
                try {
                    persistenceService.fail(
                            feedbackId,
                            failureCode(e)
                    );
                } catch (Exception failureUpdateException) {
                    log.error(
                            "Failed to persist AI feedback failure state. feedbackId={}",
                            feedbackId,
                            failureUpdateException
                    );
                }
            }
        }
    }

    private String failureCode(Exception e) {
        String message = e.getMessage();

        if (message != null
                && message.contains(
                "OPENAI_API_KEY"
        )) {
            return "MODEL_NOT_CONFIGURED";
        }

        if (message != null
                && message.contains(
                "unknown reference"
        )) {
            return "INVALID_MODEL_OUTPUT";
        }

        return "MODEL_GENERATION_FAILED";
    }
}