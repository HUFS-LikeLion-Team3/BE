package com.finsight.feedback.service;

import com.finsight.feedback.client.AiRagJobClient;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import com.finsight.global.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-only recovery for a failed AI feedback whose AI job was accepted. The AI job
 * is retried on its original frozen snapshot (no new input, no new submission), then
 * the feedback returns to generating so the regular poller stores the new result.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackAdminRetryService {
    /** AI states that mean a retry has already been accepted for this job. */
    private static final Set<String> REOPENED = Set.of("pending", "generating", "awaiting_review", "ready");

    private final AiFeedbackRepository feedbackRepository;
    private final AiRagJobRepository jobRepository;
    private final AiRagJobClient client;
    private final TransactionTemplate transaction;

    public Result retry(UUID feedbackId, UUID adminUserId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "재시도 사유를 1~500자로 입력해 주세요.");
        }
        UUID jobId = transaction.execute(status -> {
            AiFeedback feedback = feedbackRepository.findById(feedbackId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "피드백을 찾을 수 없습니다."));
            AiRagJob job = jobRepository.findById(feedbackId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "AI 작업이 없는 피드백은 재시도할 수 없습니다."));
            boolean failed = feedback.getStatus() == AiFeedback.FeedbackStatus.failed
                    && "accepted".equals(job.getState());
            if (job.getJobId() == null || !failed && !aiReconciliation(feedback, job)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "실패했거나 AI 응답 확인이 필요한 접수 작업만 재시도할 수 있습니다.");
            }
            return job.getJobId();
        });

        String aiStatus;
        try {
            aiStatus = (String) client.retryFailedJob(jobId, adminUserId, reason).get("status");
        } catch (AiRagJobClient.AiRagHttpException ex) {
            // A previous admin call may have reopened the AI job before the BE update was lost.
            Map<String, Object> current = client.getJobStatus(jobId);
            aiStatus = current.get("status") instanceof String s ? s : null;
            if (ex.getStatusCode() != 409 || !REOPENED.contains(aiStatus)) {
                throw new ApiException(HttpStatus.CONFLICT, "AI 서버가 재시도를 거절했습니다.");
            }
        }
        if (!REOPENED.contains(aiStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, "AI 작업이 재시도 상태가 아닙니다.");
        }

        String reopenedStatus = aiStatus;
        transaction.executeWithoutResult(status -> {
            AiFeedback feedback = feedbackRepository.findByIdForUpdate(feedbackId).orElseThrow();
            AiRagJob job = jobRepository.findByIdForUpdate(feedbackId).orElseThrow();
            if (feedback.getStatus() == AiFeedback.FeedbackStatus.failed) {
                feedback.reopenFailedForRetry();
            }
            if ("reconciliation_required".equals(job.getState())) {
                job.reopenAfterAiOperatorRetry(reopenedStatus);
            } else {
                job.updateStatus(reopenedStatus, null);
            }
        });
        log.info("AI feedback retry requested by admin. feedbackId={}, aiStatus={}", feedbackId, reopenedStatus);
        return new Result(feedbackId, "generating", reopenedStatus);
    }

    /** The AI asked for reconciliation (e.g. provider timeout) while BE was still waiting. */
    private static boolean aiReconciliation(AiFeedback feedback, AiRagJob job) {
        return feedback.getStatus() == AiFeedback.FeedbackStatus.generating
                && "reconciliation_required".equals(job.getState())
                && "ai_reconciliation_required".equals(job.getFailureCode());
    }

    public record Result(UUID feedbackId, String status, String aiStatus) {}
}
