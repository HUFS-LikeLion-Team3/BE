package com.finsight.feedback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "source_document_targets")
@IdClass(FeedbackSourceDocumentTarget.Key.class)
@Immutable
@Getter
@NoArgsConstructor
public class FeedbackSourceDocumentTarget {

    @Id
    @Column(name = "source_document_id", nullable = false)
    private UUID sourceDocumentId;

    @Id
    @Column(name = "market_target_id", nullable = false)
    private UUID marketTargetId;

    @Getter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        private UUID sourceDocumentId;
        private UUID marketTargetId;

        public Key(
                UUID sourceDocumentId,
                UUID marketTargetId
        ) {
            this.sourceDocumentId = sourceDocumentId;
            this.marketTargetId = marketTargetId;
        }
    }
}