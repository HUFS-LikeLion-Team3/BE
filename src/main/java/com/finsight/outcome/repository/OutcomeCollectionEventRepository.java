package com.finsight.outcome.repository;

import com.finsight.outcome.entity.OutcomeCollectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutcomeCollectionEventRepository
        extends JpaRepository<OutcomeCollectionEvent, UUID> {

    List<OutcomeCollectionEvent>
    findAllBySessionTargetIdOrderByOccurredAtAscIdAsc(
            UUID sessionTargetId
    );
}