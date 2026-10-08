package com.finsight.feedback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "ai_feedback_item_references")
@Getter
@NoArgsConstructor
public class AiFeedbackItemReference {

    @EmbeddedId
    private AiFeedbackItemReferenceId id;

    public UUID getAiFeedbackItemId() {
        return id.getAiFeedbackItemId();
    }

    public UUID getAiFeedbackReferenceId() {
        return id.getAiFeedbackReferenceId();
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class AiFeedbackItemReferenceId implements Serializable {

        @Column(name = "ai_feedback_item_id", nullable = false)
        private UUID aiFeedbackItemId;

        @Column(name = "ai_feedback_reference_id", nullable = false)
        private UUID aiFeedbackReferenceId;
    }
}