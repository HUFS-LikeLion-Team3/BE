package com.finsight.news.service;

import com.finsight.feedback.entity.FeedbackSourceDocument;
import com.finsight.feedback.entity.FeedbackSourceDocumentChunk;
import com.finsight.global.exception.ApiException;
import com.finsight.market.entity.MarketTarget;
import com.finsight.news.dto.NewsPublicationRequest;
import com.finsight.news.dto.NewsResponses.Detail;
import com.finsight.news.entity.News;
import com.finsight.news.entity.NewsTargetCandidate;
import com.finsight.news.repository.NewsTargetCandidateRepository;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NewsPublicationService {
    private final EntityManager entityManager;
    private final NewsTargetCandidateRepository candidates;
    private final TransactionTemplate transaction;
    private final Set<UUID> adminUserIds;

    public NewsPublicationService(EntityManager entityManager, NewsTargetCandidateRepository candidates,
                                  PlatformTransactionManager manager,
                                  @Value("${finsight.news-admin.user-ids:}") String adminUserIds) {
        this.entityManager = entityManager;
        this.candidates = candidates;
        this.transaction = new TransactionTemplate(manager);
        this.adminUserIds = Arrays.stream(adminUserIds.split(",")).map(String::strip)
                .filter(id -> !id.isEmpty()).map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
    }

    public void requireAdmin(UUID userId) {
        if (!adminUserIds.contains(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "뉴스 관리자 권한이 필요합니다.");
        }
    }

    public Detail publish(NewsPublicationRequest request) {
        validateUrl(request.source().url().strip());
        var targetIds = request.targets().stream().map(NewsPublicationRequest.Target::marketTargetId).toList();
        if (Set.copyOf(targetIds).size() != targetIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "같은 관측 지표를 중복 선택할 수 없습니다.");
        }

        String contentHash = digest(request.source().excerpt().strip());
        try {
            return transaction.execute(status -> {
                Detail published = findExisting(request, contentHash);
                return published != null ? published : insert(request, contentHash, targetIds);
            });
        } catch (DataIntegrityViolationException | ConstraintViolationException conflict) {
            Detail published = transaction.execute(status -> findExisting(request, contentHash));
            if (published != null) return published;
            throw new ApiException(HttpStatus.CONFLICT, "이미 등록된 원문이거나 등록 정보가 충돌합니다.");
        }
    }

    private Detail insert(NewsPublicationRequest request, String contentHash, List<UUID> targetIds) {
        List<MarketTarget> targets = targetIds.stream().map(id -> {
            MarketTarget target = entityManager.find(MarketTarget.class, id);
            if (target == null || !target.isActive()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "선택할 수 없는 관측 지표가 포함되어 있습니다.");
            }
            return target;
        }).toList();

        Instant now = Instant.now();
        Instant publishedAt = request.publishedAt().truncatedTo(ChronoUnit.SECONDS);
        News news = News.publishLive(request.title().strip(), request.category(), request.briefing().strip(),
                publishedAt, referenceAt(request), now);
        entityManager.persist(news);

        var source = request.source();
        entityManager.persist(FeedbackSourceDocument.primaryNewsSource(request.requestId(), news,
                source.type().name(), source.type().tier(), source.publisher().strip(), source.url().strip(),
                publishedAt.atOffset(ZoneOffset.UTC), contentHash, now));
        entityManager.flush();
        entityManager.persist(new FeedbackSourceDocumentChunk(request.requestId(), source.excerpt().strip(),
                now.atOffset(ZoneOffset.UTC)));

        for (int index = 0; index < targets.size(); index++) {
            entityManager.persist(new NewsTargetCandidate(news, targets.get(index), index + 1,
                    request.targets().get(index).rationale().strip()));
        }
        return Detail.from(news);
    }

    private Detail findExisting(NewsPublicationRequest request, String contentHash) {
        var source = entityManager.find(FeedbackSourceDocument.class, request.requestId());
        if (source == null) return null;

        var news = source.getNews();
        var input = request.source();
        if (news != null && source.isPrimary() && Objects.equals(source.getContentHash(), contentHash)
                && Objects.equals(source.getUrl(), input.url().strip())
                && Objects.equals(source.getPublisher(), input.publisher().strip())
                && Objects.equals(source.getSourceType(), input.type().name())
                && Objects.equals(news.getTitle(), request.title().strip())
                && Objects.equals(news.getCategory(), request.category())
                && Objects.equals(news.getBriefing(), request.briefing().strip())
                && Objects.equals(news.getPublishedAt(), request.publishedAt().truncatedTo(ChronoUnit.SECONDS))
                && Objects.equals(news.getReferenceAt(), referenceAt(request))
                && candidates.findCandidates(news.getId()).stream()
                        .map(candidate -> new NewsPublicationRequest.Target(candidate.getTarget().getId(), candidate.getRationale()))
                        .toList().equals(request.targets().stream()
                                .map(target -> new NewsPublicationRequest.Target(target.marketTargetId(), target.rationale().strip())).toList())) {
            return Detail.from(news);
        }
        throw new ApiException(HttpStatus.CONFLICT, "이미 사용된 등록 요청입니다.");
    }

    private static Instant referenceAt(NewsPublicationRequest request) {
        return (request.referenceAt() == null ? request.publishedAt() : request.referenceAt()).truncatedTo(ChronoUnit.SECONDS);
    }

    private static void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null && uri.getUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid syntax and unsupported URL types return the same field error.
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "원문 URL은 올바른 HTTP 또는 HTTPS 주소여야 합니다.");
    }

    private static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
