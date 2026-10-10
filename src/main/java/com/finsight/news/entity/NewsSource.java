package com.finsight.news.entity;

import com.finsight.feedback.entity.FeedbackSourceDocument;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Entity
@Table(name = "news_sources", uniqueConstraints = @UniqueConstraint(columnNames = {"news_id", "source_document_id"}))
@Getter
@NoArgsConstructor
public class NewsSource {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "news_id", nullable = false) private UUID newsId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_document_id", nullable = false)
    private FeedbackSourceDocument document;
}
