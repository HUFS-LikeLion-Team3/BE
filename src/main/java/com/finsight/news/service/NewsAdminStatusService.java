package com.finsight.news.service;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.news.entity.News;
import com.finsight.news.repository.NewsRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NewsAdminStatusService {
    private static final int PAGE_SIZE = 20;

    private final NewsRepository news;
    private final LearningSessionRepository sessions;

    @Transactional(readOnly = true)
    public NewsPage list(News.Status status, int page) {
        if (page < 0 || page > Integer.MAX_VALUE / PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "올바르지 않은 페이지 번호입니다.");
        }
        var pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.asc("id")));
        var result = status == null ? news.findAll(pageable) : news.findAllByStatus(status.name(), pageable);
        return new NewsPage(result.map(Item::from).getContent(), page, result.hasNext());
    }

    @Transactional
    public Item change(UUID id, News.Status expected, News.Status next) {
        // Session creation takes a shared lock on the same article before it writes a session.
        News article = news.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "해당 뉴스를 찾을 수 없습니다."));
        News.Status current = News.Status.valueOf(article.getStatus());
        if (current == next) return Item.from(article);
        if (current != expected) {
            throw new ApiException(HttpStatus.CONFLICT, "다른 관리자가 뉴스 상태를 변경했습니다.");
        }
        if (current == News.Status.published && sessions.existsByNewsId(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "학습 기록이 있는 뉴스는 비공개로 변경할 수 없습니다.");
        }
        article.changeStatus(next);
        return Item.from(article);
    }

    public record Item(UUID id, String title, String category, String contentType, News.Status status,
                       Instant publishedAt) {
        static Item from(News news) {
            return new Item(news.getId(), news.getTitle(), news.getCategory(), news.getContentType(),
                    News.Status.valueOf(news.getStatus()), news.getPublishedAt());
        }
    }

    public record NewsPage(List<Item> content, int page, boolean hasNext) {}
}
