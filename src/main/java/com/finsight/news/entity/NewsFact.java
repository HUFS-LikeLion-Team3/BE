package com.finsight.news.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "news_facts", indexes = @Index(name = "idx_news_facts_news_order", columnList = "news_id,sort_order"))
@Getter
@NoArgsConstructor
public class NewsFact {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "news_id", nullable = false)
    private UUID newsId;
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
