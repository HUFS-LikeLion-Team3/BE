package com.finsight.feedback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "source_document_chunks")
@Immutable
@Getter
@NoArgsConstructor
public class FeedbackSourceDocumentChunk {

    @Id
    private UUID id;

    @Column(name = "source_document_id", nullable = false)
    private UUID sourceDocumentId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "chunk_order", nullable = false)
    private Integer chunkOrder;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}