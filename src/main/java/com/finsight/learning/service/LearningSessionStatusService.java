package com.finsight.learning.service;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.SessionTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class LearningSessionStatusService {
    private static final Set<Integer> HORIZONS = Set.of(1, 5, 20);

    private final LearningSessionRepository learningSessionRepository;
    private final SessionTargetRepository sessionTargetRepository;
    private final SessionTargetBaselineRepository baselineRepository;
    private final MarketObservationRepository observationRepository;

    @Transactional
    public LearningSession.LearningStatus reconcile(UUID sessionId) {
        LearningSession session = learningSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown learning session: " + sessionId));
        var current = session.getStatus();
        if (current == LearningSession.LearningStatus.drafting
                || current == LearningSession.LearningStatus.completed
                || current == LearningSession.LearningStatus.reflection_pending) {
            return current;
        }

        List<SessionTarget> selected = sessionTargetRepository.findAllByLearningSessionId(sessionId)
                .stream().filter(SessionTarget::isSelected).toList();
        if (selected.isEmpty()) {
            return current; // No usable submitted targets; never transition on missing input.
        }

        // T0 is considered closed only after every *selected* target is ready or unavailable.
        boolean baselinesClosed = selected.stream().allMatch(target -> baselineRepository
                .findBySessionTargetId(target.getId())
                .map(b -> b.getStatus() != SessionTargetBaseline.BaselineStatus.pending)
                .orElse(false));
        if (!baselinesClosed) {
            return current;
        }

        // Missing rows are NOT terminal. Even a failed T0 must have all three
        // unavailable observation rows written atomically by the collection pipeline.
        boolean observationsClosed = selected.stream().allMatch(this::allHorizonsClosed);
        session.advanceMarketStatus(observationsClosed
                ? LearningSession.LearningStatus.reflection_pending
                : LearningSession.LearningStatus.observing);
        return session.getStatus();
    }

    private boolean allHorizonsClosed(SessionTarget target) {
        List<MarketObservation> observations = observationRepository
                .findAllBySessionTargetIdOrderByHorizonAsc(target.getId());
        return HORIZONS.stream().allMatch(h -> observations.stream().anyMatch(o ->
                o.getHorizon() == h && (o.getStatus() == MarketObservation.OutcomeStatus.ready
                        || o.getStatus() == MarketObservation.OutcomeStatus.unavailable)));
    }
}
