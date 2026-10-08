package com.finsight.feedback.repository;

import com.finsight.feedback.entity.AiFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiFeedbackRepository extends JpaRepository<AiFeedback, UUID> {

    List<AiFeedback> findAllByLearningSessionId(UUID learningSessionId);
}