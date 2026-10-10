package com.finsight.news.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "news")
@Getter
@NoArgsConstructor
public class News {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private String title;
    @Column(nullable = false) private String category;
    @Column(nullable = false, columnDefinition = "TEXT") private String briefing;
    @Column(nullable = false) private String contentType;
    @Column(nullable = false) private String replayStatus;
    @Column(nullable = false) private String status;
    private Instant publishedAt;
    private Instant referenceAt;
    private Instant curatedAt;
    @ElementCollection @CollectionTable(name = "news_markets", joinColumns = @JoinColumn(name = "news_id"))
    @Column(name = "market", nullable = false)
    private Set<String> markets = new HashSet<>();
    @ElementCollection @CollectionTable(name = "news_topics", joinColumns = @JoinColumn(name = "news_id"))
    @Column(name = "topic", nullable = false)
    private Set<String> topics = new HashSet<>();
}
