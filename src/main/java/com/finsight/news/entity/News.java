package com.finsight.news.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "news", indexes = {
    @Index(name = "idx_news_status_curated", columnList = "status,curated_at"),
    @Index(name = "idx_news_content_replay", columnList = "content_type,replay_status"),
    @Index(name = "idx_news_category_published", columnList = "category,published_at")})
@Getter
@NoArgsConstructor
@org.hibernate.annotations.Check(constraints = "content_type in ('live','replay') and replay_status in ('not_eligible','eligible','featured') and status in ('draft','published','archived')")
public class News {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private String title;
    @Column(nullable = false) private String category;
    @Column(nullable = false, columnDefinition = "TEXT") private String briefing;
    @org.hibernate.annotations.ColumnDefault("'live'")
    @Column(nullable = false) private String contentType = "live";
    @org.hibernate.annotations.ColumnDefault("'not_eligible'")
    @Column(nullable = false) private String replayStatus = "not_eligible";
    @org.hibernate.annotations.ColumnDefault("'draft'")
    @Column(nullable = false) private String status = "draft";
    @Column(nullable = false) private Instant publishedAt;
    @Column(nullable = false) private Instant referenceAt;
    private Instant curatedAt;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @PrePersist void create() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void update() { updatedAt = Instant.now(); }
}
