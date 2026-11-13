
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NewsService {

    private static final ZoneId KOREA_ZONE =
            ZoneId.of("Asia/Seoul");

    private final NewsRepository news;
    private final UserInterestRepository interests;
    private final NewsFactRepository facts;
    private final FeedbackSourceDocumentRepository documents;
    private final EntityManager em;
    private final NewsTargetCandidateRepository candidates;

    public ListResponse list(
            UUID userId,
            String category,
            String market,
            String contentType,
            String replayStatus,
            String sort,
            int page,
            int size
    ) {
        return list(
                userId,
                category,
                market,
                contentType,
                replayStatus,
                sort,
                page,
                size,
                null,
                null,
                null
        );
    }

    public ListResponse list(
            UUID userId,
            String category,
            String market,
            String contentType,
            String replayStatus,
            String sort,
            int page,
            int size,
            LocalDate from,
            LocalDate to,
            UUID marketTargetId
    ) {
        if (contentType == null) {
            contentType = "live";
        }

        if (market == null) {
            market = "all";
        }

        if (page < 0
                || size < 1
                || size > 100
                || (long) page * size > Integer.MAX_VALUE
                || !Set.of("live", "replay").contains(contentType)
                || !Set.of("latest", "recommended").contains(sort)
                || !Set.of("korea", "us", "all").contains(market)
                || (category != null
                && (category.isBlank()
                || category.length() > 255))
                || (replayStatus != null
                && !Set.of(
                "not_eligible",
                "eligible",
                "featured"
        ).contains(replayStatus))
                || (from != null
                && to != null
                && from.isAfter(to))) {
            throw invalid();
        }

        Map<String, Object> parameters = new HashMap<>();

        StringBuilder query = new StringBuilder(
                "select n from News n where n.status = 'published'"
        );

        query.append(" and n.contentType = :contentType");
        parameters.put("contentType", contentType);

        if (!"all".equals(market)) {
            query.append("""
                     and exists (
                         select c
                         from NewsTargetCandidate c
                         where c.news = n
                           and c.target.marketCategory = :marketCategory
                     )
                    """);

            parameters.put(
                    "marketCategory",
                    "korea".equals(market)
                            ? "korea_equity"
                            : "us_equity"
            );
        }

        if (category != null) {
            query.append(" and n.category = :category");
            parameters.put("category", category);
        }

        if (replayStatus != null) {
            query.append(" and n.replayStatus = :replayStatus");
            parameters.put("replayStatus", replayStatus);
        }

        if (from != null) {
            query.append(
                    " and n.publishedAt >= :fromInclusive"
            );

            parameters.put(
                    "fromInclusive",
                    from.atStartOfDay(KOREA_ZONE).toInstant()
            );
        }

        if (to != null) {
            query.append(
                    " and n.publishedAt < :toExclusive"
            );

            parameters.put(
                    "toExclusive",
                    to.plusDays(1)
                            .atStartOfDay(KOREA_ZONE)
                            .toInstant()
            );
        }

        if (marketTargetId != null) {
            query.append("""
                     and exists (
                         select c
                         from NewsTargetCandidate c
                         where c.news = n
                           and c.target.id = :marketTargetId
                     )
                    """);

            parameters.put(
                    "marketTargetId",
                    marketTargetId
            );
        }

        List<String> scores = new ArrayList<>();

        if ("recommended".equals(sort)) {
            int index = 0;

            for (UserInterest interest
                    : interests.findByUserId(userId)) {

                String key = "interest" + index++;

                if (interest.getInterestType()
                        == UserInterest.InterestType.market) {
                    scores.add(
                            "(case when exists (select c "
                                    + "from NewsTargetCandidate c "
                                    + "where c.news = n "
                                    + "and c.target.marketCategory = :"
                                    + key
                                    + ") then 1 else 0 end)"
                    );
                } else {
                    scores.add(
                            "(case when n.category = :"
                                    + key
                                    + " then 1 else 0 end)"
                    );
                }

                String interestKey =
                        interest.getInterestKey();

                if (interest.getInterestType()
                        == UserInterest.InterestType.market) {
                    interestKey = switch (interestKey) {
                        case "korea" -> "korea_equity";
                        case "us" -> "us_equity";
                        default -> interestKey;
                    };
                }

                parameters.put(key, interestKey);
            }
        }

        query.append(" order by ");

        if (!scores.isEmpty()) {
            query.append(String.join(" + ", scores))
                    .append(" desc, ");
        }

        query.append("n.publishedAt desc, n.id asc");

        var typedQuery =
                em.createQuery(query.toString(), News.class);

        parameters.forEach(typedQuery::setParameter);

        var rows = typedQuery
                .setFirstResult(page * size)
                .setMaxResults(size + 1)
                .getResultList();

        return new ListResponse(
                rows.stream()
                        .limit(size)
                        .map(Item::from)
                        .toList(),
                page,
                size,
                rows.size() > size
        );
    }

    private ApiException invalid() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "유효하지 않은 뉴스 조회 조건입니다."
        );
    }

    private News published(UUID id) {
        return news.findByIdAndStatus(id, "published")
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                "뉴스를 찾을 수 없습니다."
                        )
                );
    }

    public Detail detail(UUID id) {
        return Detail.from(published(id));
    }

    public Candidates targetCandidates(UUID id) {
        published(id);

        return new Candidates(
                candidates.findCandidates(id)
                        .stream()
                        .map(Candidate::from)
                        .toList()
        );
    }

    public Facts facts(UUID id) {
        published(id);

        return new Facts(
                facts.findByNewsIdOrderBySortOrderAscIdAsc(id)
                        .stream()
                        .map(Fact::from)
                        .toList()
        );
    }

    public Sources sources(UUID id) {
        published(id);

        return new Sources(
                documents.findByNewsIdOrderByIdAsc(id)
                        .stream()
                        .map(Source::from)
                        .toList()
        );
    }

    public Document document(UUID id) {
        return Document.from(
                documents.findById(id)
                        .orElseThrow(() ->
                                new ApiException(
                                        HttpStatus.NOT_FOUND,
                                        "출처 문서를 찾을 수 없습니다."
                                )
                        )
        );
    }
}
