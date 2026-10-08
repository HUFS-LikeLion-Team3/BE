package com.finsight.outcome.repository;

import com.finsight.outcome.entity.MarketObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MarketObservationRepository
        extends JpaRepository<MarketObservation, UUID> {

    List<MarketObservation>
    findAllBySessionTargetIdOrderByHorizonAsc(
            UUID sessionTargetId
    );
}