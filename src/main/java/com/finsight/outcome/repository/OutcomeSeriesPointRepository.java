package com.finsight.outcome.repository;

import com.finsight.outcome.entity.OutcomeSeriesPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutcomeSeriesPointRepository
        extends JpaRepository<OutcomeSeriesPoint, UUID> {

    List<OutcomeSeriesPoint>
    findAllBySessionTargetIdOrderByTradingDateAsc(
            UUID sessionTargetId
    );
}