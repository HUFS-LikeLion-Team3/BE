package com.finsight.feedback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "ai_feedback_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ai_feedback_items_feedback_order",
                columnNames = {"ai_feedback_id", "sort_order"}
        )
)
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables_to_check", columnDefinition = "json")
    private List<String> variablesToCheck;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    public static AiFeedbackItem create(
            UUID aiFeedbackId,
            UUID sessionTargetId,
            FeedbackItemType itemType,
            String content,
            List<String> variablesToCheck,
            int sortOrder
    ) {
        AiFeedbackItem item = new AiFeedbackItem();
        item.aiFeedbackId = aiFeedbackId;
        item.sessionTargetId = sessionTargetId;
        item.itemType = itemType;
        item.content = content;
        item.variablesToCheck = variablesToCheck;
        item.sortOrder = sortOrder;
        return item;
    }

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