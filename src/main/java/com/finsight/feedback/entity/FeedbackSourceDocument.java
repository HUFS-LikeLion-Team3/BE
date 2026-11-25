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

@Entity(name = "FeedbackSourceDocument")
@Table(name = "source_documents", uniqueConstraints = @jakarta.persistence.UniqueConstraint(
    name = "uk_source_url_hash", columnNames = {"url", "content_hash"}), indexes = {
    @jakarta.persistence.Index(name = "idx_source_news_primary", columnList = "news_id,is_primary"),
    @jakarta.persistence.Index(name = "idx_source_published_tier", columnList = "published_at,selection_tier")})
@Immutable
@Getter
@NoArgsConstructor
@org.hibernate.annotations.Check(constraints = "source_type in ('original_article','official_statistic','government_release','central_bank_release','research_report') and selection_tier in ('primary_official','original_article','trusted_research')")
public class FeedbackSourceDocument {

    public static FeedbackSourceDocument primaryNewsSource(
            UUID id, com.finsight.news.entity.News news, String sourceType, String selectionTier,
            String publisher, String url, String publicSummary, OffsetDateTime publishedAt, String contentHash,
            java.time.Instant retrievedAt) {
        FeedbackSourceDocument source = new FeedbackSourceDocument();
        source.id = id;
        source.news = news;
        source.sourceType = sourceType;
        source.selectionTier = selectionTier;
        source.primary = true;
        source.publisher = publisher;
        source.title = news.getTitle();
        source.url = url;
        source.publicSummary = publicSummary;
        source.publishedAt = publishedAt;
        source.availableAt = publishedAt;
        source.contentHash = contentHash;
        source.retrievedAt = retrievedAt;
        return source;
    }

    @Id
    private UUID id;

    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "news_id")
    private com.finsight.news.entity.News news;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "selection_tier", nullable = false)
    private String selectionTier;

    @Column(name = "is_primary", nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean primary;

    @Column(name = "retrieved_at", nullable = false)
    private java.time.Instant retrievedAt;

    @Column(name = "content_storage_uri")
    private String contentStorageUri;
    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private java.time.Instant updatedAt;
    @jakarta.persistence.PrePersist
    void create() {
        if (createdAt == null) createdAt = java.time.Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    @Column(nullable = false)
    private String publisher;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String url;

    @Column(name = "public_summary", columnDefinition = "TEXT")
    private String publicSummary;

    @Column(name = "published_at", nullable = false)
    private OffsetDateTime publishedAt;

    @Column(name = "available_at")
    private OffsetDateTime availableAt;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;
}
