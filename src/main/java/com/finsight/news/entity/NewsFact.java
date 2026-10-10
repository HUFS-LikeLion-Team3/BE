package com.finsight.news.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "news_facts", uniqueConstraints = @UniqueConstraint(name = "uk_news_fact_order", columnNames = {"news_id", "sort_order"}))
@Getter
@NoArgsConstructor
public class NewsFact {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "news_id", nullable = false)
    private UUID newsId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "news_id", insertable = false, updatable = false)
    private News news;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_document_id")
    private com.finsight.feedback.entity.FeedbackSourceDocument sourceDocument;
    @Column(nullable = false)
    private String label;
    @Column(name = "value_text", nullable = false)
    private String valueText;
    private String unit;
    @Column(name = "as_of_at")
    private Instant asOfAt;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public NewsFact(UUID newsId, String label, String valueText, String unit, Instant asOfAt, int sortOrder) {
        this.newsId = newsId;
        this.label = label;
        this.valueText = valueText;
        this.unit = unit;
        this.asOfAt = asOfAt;
        this.sortOrder = sortOrder;
    }
}
