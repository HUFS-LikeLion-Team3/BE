
package com.finsight.outcome.service;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "finsight.outcome.collection.enabled",
        havingValue = "true"
)
public class OutcomeBaselineScheduler {

    private static final List<LearningSession.LearningStatus>
            ACTIVE_STATUSES = List.of(
            LearningSession.LearningStatus.baseline_pending,
            LearningSession.LearningStatus.observing
    );

    private final LearningSessionRepository sessionRepository;
    private final OutcomeBaselineCoordinator baselineCoordinator;
    private final OutcomeCollectionService collectionService;

    @Value("${finsight.outcome.collection.page-size:100}")
    private int pageSize = 100;

    @Scheduled(
            fixedDelayString =
                    "${finsight.outcome.collection.fixed-delay-ms:3600000}",
            initialDelayString =
                    "${finsight.outcome.collection.initial-delay-ms:30000}"
    )
    public void collectBaselines() {

        List<UUID> sessionIds;

        try {
            sessionIds = findCandidateSessionIds();
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to find baseline collection candidates",
                    exception
            );
            return;
        }

        log.info(
                "Starting baseline collection for {} sessions",
                sessionIds.size()
        );

        for (UUID sessionId : sessionIds) {
            collectSession(sessionId);
        }
    }

    private List<UUID> findCandidateSessionIds() {

        List<UUID> result = new ArrayList<>();

        int pageNumber = 0;
        int size = Math.max(1, pageSize);

        Page<LearningSession> page;

        do {
            page = sessionRepository.findAllByStatusIn(
                    ACTIVE_STATUSES,
                    PageRequest.of(
                            pageNumber,
                            size,
                            Sort.by(Sort.Direction.ASC, "id")
                    )
            );

            for (LearningSession session : page.getContent()) {

                if (session.getSessionType()
                        != LearningSession.SessionType.live) {
                    continue;
                }

                if (session.getSubmittedAt() == null) {
                    continue;
                }

                result.add(session.getId());
            }

            pageNumber++;

        } while (page.hasNext());

        return result;
    }

    private void collectSession(UUID sessionId) {

        List<OutcomeBaselineCoordinator.BaselineAttempt> attempts;

        try {
            attempts = baselineCoordinator.runOnce(sessionId);

        } catch (RuntimeException exception) {
            log.warn(
                    "Baseline coordination failed for session {}. Error type: {}",
                    sessionId,
                    exception.getClass().getSimpleName()
            );
            return;
        }

        for (OutcomeBaselineCoordinator.BaselineAttempt attempt : attempts) {

            try {
                handleAttempt(sessionId, attempt);

            } catch (RuntimeException exception) {
                log.warn(
                        "Baseline attempt handling failed: sessionId={}, sessionTargetId={}, status={}, errorType={}",
                        sessionId,
                        attempt.sessionTargetId(),
                        attempt.status(),
                        exception.getClass().getSimpleName()
                );
            }
        }
    }

    private void handleAttempt(
            UUID sessionId,
            OutcomeBaselineCoordinator.BaselineAttempt attempt
    ) {

        UUID sessionTargetId = attempt.sessionTargetId();

        switch (attempt.status()) {

            case READY -> log.info(
                    "Baseline ready: sessionTargetId={}, date={}",
                    sessionTargetId,
                    attempt.checkedDate()
            );

            case WAITING -> log.debug(
                    "Baseline waiting: sessionTargetId={}",
                    sessionTargetId
            );

            case RETRY_LATER -> log.debug(
                    "Baseline retry required: sessionTargetId={}",
                    sessionTargetId
            );

            case ALREADY_FINALIZED -> log.debug(
                    "Baseline already finalized: sessionTargetId={}",
                    sessionTargetId
            );

            case UNSUPPORTED_DAILY_MARKET -> log.warn(
                    "Daily market calendar is not supported yet: sessionTargetId={}",
                    sessionTargetId
            );

            case PROVIDER_UNAVAILABLE -> log.warn(
                    "Market provider unavailable: sessionTargetId={}",
                    sessionTargetId
            );

            case DEADLINE_PASSED_UNRESOLVED -> {

                collectionService.expireBaseline(
                        sessionId,
                        sessionTargetId,
                        "BASELINE_DEADLINE_EXCEEDED"
                );

                log.info(
                        "Baseline expired: sessionTargetId={}",
                        sessionTargetId
                );
            }
        }
    }
}
