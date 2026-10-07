package com.finsight.prediction.repository;

import com.finsight.prediction.entity.Prediction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PredictionRepository
        extends JpaRepository<Prediction, UUID> {

    Optional<Prediction> findBySessionTargetIdAndHorizon(
            UUID sessionTargetId,
            Integer horizon
    );

    List<Prediction> findAllBySessionTargetIdOrderByHorizonAsc(
            UUID sessionTargetId
    );
}
