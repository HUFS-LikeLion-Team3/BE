package com.finsight.outcome.repository;

import com.finsight.outcome.entity.OutcomeSeriesPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutcomeSeriesPointRepository
        extends JpaRepository<OutcomeSeriesPoint, UUID> {

    List<OutcomeSeriesPoint>
    findAllBySessionTargetIdOrderByTradingDateAsc(
            UUID sessionTargetId
    );

    Optional<OutcomeSeriesPoint>
    findBySessionTargetIdAndTradingDate(
            UUID sessionTargetId,
            LocalDate tradingDate
    );
}