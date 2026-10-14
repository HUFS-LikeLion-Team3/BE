package com.finsight.reflection.repository;

import com.finsight.reflection.entity.ReflectionNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReflectionNoteRepository extends JpaRepository<ReflectionNote, UUID> {
    List<ReflectionNote> findAllByLearningSessionIdOrderByCreatedAtAscIdAsc(UUID learningSessionId);
    List<ReflectionNote> findAllBySubmissionIdOrderByCreatedAtAscIdAsc(UUID submissionId);
}
