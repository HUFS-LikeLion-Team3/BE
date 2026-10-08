package com.finsight.feedback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "ai_feedback_items")
@Getter
@NoArgsConstructor
public class AiFeedbackItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ai_feedback_id", nullable = false)
    private UUID aiFeedbackId;

    @Column(name = "session_target_id")
    private UUID sessionTargetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false)
    private FeedbackItemType itemType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    public enum FeedbackItemType {
        flow_summary,
        initial_uncertainty,
        initial_counter_scenario,
        initial_factor_to_review,
        final_observed_flow,
        final_co_observed_factors,
        final_reflection_improvement,
        final_next_hypothesis,
        interpretation_limit
    }
}