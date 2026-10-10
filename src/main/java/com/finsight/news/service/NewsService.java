package com.finsight.news.service;

import com.finsight.news.dto.NewsResponses.*;
import com.finsight.news.entity.*;
import com.finsight.news.repository.*;
import com.finsight.feedback.repository.FeedbackSourceDocumentRepository;
import com.finsight.global.exception.ApiException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NewsService {
    private final NewsRepository news;
    private final UserNewsInterestRepository interests;
    private final NewsSourceRepository sources;
    private final FeedbackSourceDocumentRepository documents;
    private final EntityManager em;

    public ListResponse list(UUID userId, String category, String contentType, String replayStatus,
                             String sort, String pageValue, String sizeValue) {
        int page;
        int size;
        try {
            page = Integer.parseInt(pageValue);
            size = Integer.parseInt(sizeValue);
        } catch (NumberFormatException e) { throw invalid(); }
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE
                || !Set.of("live", "replay").contains(contentType)
                || !Set.of("latest", "recommended").contains(sort)
                || (category != null && !Set.of("rate").contains(category))
                || (replayStatus != null && !Set.of("not_eligible").contains(replayStatus))) {
            throw invalid();
        }
        var parameters = new HashMap<String, Object>();
        StringBuilder query = new StringBuilder("select n from News n where n.status = 'published' and n.contentType = :contentType");
        parameters.put("contentType", contentType);
        if (category != null) { query.append(" and n.category = :category"); parameters.put("category", category); }
        if (replayStatus != null) { query.append(" and n.replayStatus = :replayStatus"); parameters.put("replayStatus", replayStatus); }
        var scores = new ArrayList<String>();
        if ("recommended".equals(sort)) {
            interests.findById(userId).ifPresent(i -> {
                // Each matching market/topic adds one point, without filtering out other news.
                int index = 0;
                for (String market : new TreeSet<>(i.getMarkets())) {
                    String key = "market" + index++;
                    scores.add("(case when :" + key + " member of n.markets then 1 else 0 end)");
                    parameters.put(key, market);
                }
                index = 0;
                for (String topic : new TreeSet<>(i.getTopics())) {
                    String key = "topic" + index++;
                    scores.add("(case when :" + key + " member of n.topics then 1 else 0 end)");
                    parameters.put(key, topic);
                }
            });
        }
        query.append(" order by ");
        if (!scores.isEmpty()) query.append(String.join(" + ", scores)).append(" desc, ");
        query.append("n.publishedAt desc, n.id asc");
        var typedQuery = em.createQuery(query.toString(), News.class);
        parameters.forEach(typedQuery::setParameter);
        var rows = typedQuery.setFirstResult(page * size).setMaxResults(size + 1).getResultList();
        return new ListResponse(rows.stream().limit(size).map(Item::from).toList(), page, size, rows.size() > size);
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "유효하지 않은 뉴스 조회 조건입니다.");
    }
    private News published(UUID id) {
        return news.findByIdAndStatus(id, "published").orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "뉴스를 찾을 수 없습니다."));
    }
    public Detail detail(UUID id) { return Detail.from(published(id)); }
    public Sources sources(UUID id) {
        published(id);
        return new Sources(sources.findSources(id).stream().map(s -> Source.from(s.getDocument())).toList());
    }
    public Document document(UUID id) {
        return Document.from(documents.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "출처 문서를 찾을 수 없습니다.")));
    }
}
