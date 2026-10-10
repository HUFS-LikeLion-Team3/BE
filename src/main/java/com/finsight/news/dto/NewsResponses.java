package com.finsight.news.dto;

import com.finsight.news.entity.News;
import com.finsight.feedback.entity.FeedbackSourceDocument;
import java.time.*;
import java.util.*;

public final class NewsResponses {
    private NewsResponses() {}
    public record Item(UUID id, String title, String category, String briefing, String contentType,
                       String replayStatus, Instant publishedAt) {
        public static Item from(News n) {
            return new Item(n.getId(), n.getTitle(), n.getCategory(), n.getBriefing(),
                    n.getContentType(), n.getReplayStatus(), n.getPublishedAt());
        }
    }
    public record ListResponse(List<Item> content, int page, int size, boolean hasNext) {}
    public record Detail(UUID id, String contentType, String replayStatus, String title, String category,
                         String briefing, Instant publishedAt, Instant referenceAt, Instant curatedAt) {
        public static Detail from(News n) {
            return new Detail(n.getId(), n.getContentType(), n.getReplayStatus(), n.getTitle(),
                    n.getCategory(), n.getBriefing(), n.getPublishedAt(), n.getReferenceAt(), n.getCuratedAt());
        }
    }
    public record Source(UUID id, String sourceType, String selectionTier, String publisher, String title,
                         String url, OffsetDateTime publishedAt, boolean isPrimary) {
        public static Source from(FeedbackSourceDocument d) {
            return new Source(d.getId(), d.getSourceType(), d.getSelectionTier(), d.getPublisher(),
                    d.getTitle(), d.getUrl(), d.getPublishedAt(), d.isPrimary());
        }
    }
    public record Sources(List<Source> sources) {}
    public record Document(UUID id, String sourceType, String selectionTier, String publisher, String title,
                           String url, OffsetDateTime publishedAt, boolean isPrimary,
                           String contentHash, Instant retrievedAt) {
        public static Document from(FeedbackSourceDocument d) {
            return new Document(d.getId(), d.getSourceType(), d.getSelectionTier(), d.getPublisher(),
                    d.getTitle(), d.getUrl(), d.getPublishedAt(), d.isPrimary(), d.getContentHash(), d.getRetrievedAt());
        }
    }
}
