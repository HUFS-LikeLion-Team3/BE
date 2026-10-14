package com.finsight.feedback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(
        name = "ai_feedback_references",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ai_feedback_reference_chunk",
                        columnNames = {
                                "ai_feedback_id",
                                "source_document_chunk_id"
                        }
                ),
                @UniqueConstraint(
                        name = "uk_ai_feedback_reference_order",
                        columnNames = {
                                "ai_feedback_id",
                                "sort_order"
                        }
                )
        }
)
@Getter
@NoArgsConstructor
public class AiFeedbackReference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ai_feedback_id", nullable = false)
    private UUID aiFeedbackId;

    @Column(name = "source_document_id", nullable = false)
    private UUID sourceDocumentId;

    @Column(name = "source_document_chunk_id", nullable = false)
    private UUID sourceDocumentChunkId;

    @Column(name = "citation_label", nullable = false)
    private String citationLabel;

    @Column(name = "citation_excerpt", columnDefinition = "TEXT")
    private String citationExcerpt;

    @Column(name = "usage_context", columnDefinition = "TEXT")
    private String usageContext;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    public static AiFeedbackReference create(
            UUID aiFeedbackId,
            UUID sourceDocumentId,
            UUID sourceDocumentChunkId,
            String citationLabel,
            String citationExcerpt,
            String usageContext,
            int sortOrder
    ) {
        AiFeedbackReference reference = new AiFeedbackReference();
        reference.aiFeedbackId = aiFeedbackId;
        reference.sourceDocumentId = sourceDocumentId;
        reference.sourceDocumentChunkId = sourceDocumentChunkId;
        reference.citationLabel = citationLabel;
        reference.citationExcerpt = citationExcerpt;
        reference.usageContext = usageContext;
        reference.sortOrder = sortOrder;
        return reference;
    }
}