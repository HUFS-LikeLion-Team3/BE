package com.finsight.feedback.service;

import com.finsight.feedback.entity.FeedbackSourceDocument;
import com.finsight.feedback.entity.FeedbackSourceDocumentChunk;
import com.finsight.feedback.repository.FeedbackSourceDocumentChunkRepository;
import com.finsight.feedback.repository.FeedbackSourceDocumentRepository;
import com.finsight.feedback.repository.FeedbackSourceDocumentTargetRepository;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.news.entity.News;
import com.finsight.news.entity.NewsFact;
import com.finsight.news.repository.NewsFactRepository;
import com.finsight.news.repository.NewsRepository;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.prediction.entity.Prediction;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.PredictionRepository;
import com.finsight.prediction.repository.SessionTargetRepository;
import com.finsight.reflection.repository.ReflectionNoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackGenerationInputBuilder {

    private final LearningSessionRepository sessionRepository;
    private final NewsRepository newsRepository;
    private final NewsFactRepository factRepository;
    private final SessionTargetRepository sessionTargetRepository;
    private final PredictionRepository predictionRepository;
    private final MarketTargetRepository marketTargetRepository;
    private final SessionTargetBaselineRepository baselineRepository;
    private final MarketObservationRepository observationRepository;
    private final ReflectionNoteRepository reflectionNoteRepository;
    private final FeedbackSourceDocumentRepository documentRepository;
    private final FeedbackSourceDocumentTargetRepository documentTargetRepository;
    private final FeedbackSourceDocumentChunkRepository chunkRepository;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public OffsetDateTime resolveFinalCutoff(
            UUID sessionId,
            OffsetDateTime fallback
    ) {
        List<SessionTarget> targets = selectedTargets(sessionId);

        return targets.stream()
                .flatMap(target ->
                        observationRepository
                                .findAllBySessionTargetIdOrderByHorizonAsc(
                                        target.getId()
                                )
                                .stream()
                )
                .filter(observation ->
                        observation.getStatus()
                                != MarketObservation.OutcomeStatus.pending
                )
                .map(observation ->
                        observation.getObservedAt() != null
                                ? observation.getObservedAt()
                                : observation.getUpdatedAt()
                )
                .max(Comparator.naturalOrder())
                .orElse(fallback);
    }

    public FeedbackGenerationInput buildInitial(
            UUID sessionId,
            OffsetDateTime cutoff
    ) {
        return build(sessionId, cutoff, false);
    }

    public FeedbackGenerationInput buildFinal(
            UUID sessionId,
            OffsetDateTime cutoff
    ) {
        return build(sessionId, cutoff, true);
    }

    private FeedbackGenerationInput build(
            UUID sessionId,
            OffsetDateTime cutoff,
            boolean includeResults
    ) {
        LearningSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Learning session not found: " + sessionId
                        )
                );

        News news = newsRepository.findById(session.getNewsId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "News not found: " + session.getNewsId()
                        )
                );

        List<SessionTarget> selectedTargets =
                selectedTargets(sessionId);

        if (selectedTargets.isEmpty()) {
            throw new IllegalStateException(
                    "Selected target does not exist."
            );
        }

        Set<UUID> sessionTargetIds = selectedTargets.stream()
                .map(SessionTarget::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<UUID> marketTargetIds = selectedTargets.stream()
                .map(SessionTarget::getMarketTargetId)
                .toList();

        Map<UUID, MarketTarget> marketTargets =
                marketTargetRepository.findAllById(marketTargetIds)
                        .stream()
                        .collect(Collectors.toMap(
                                MarketTarget::getId,
                                Function.identity()
                        ));

        List<Prediction> predictions =
                predictionRepository.findAllBySessionTargetIdIn(
                        new ArrayList<>(sessionTargetIds)
                );

        Map<UUID, List<Prediction>> predictionsByTarget =
                predictions.stream()
                        .collect(Collectors.groupingBy(
                                Prediction::getSessionTargetId
                        ));

        List<Map<String, Object>> targetSnapshots =
                new ArrayList<>();

        for (SessionTarget sessionTarget : selectedTargets) {
            MarketTarget marketTarget =
                    marketTargets.get(
                            sessionTarget.getMarketTargetId()
                    );

            if (marketTarget == null) {
                throw new IllegalStateException(
                        "Market target not found: "
                                + sessionTarget.getMarketTargetId()
                );
            }

            List<Map<String, Object>> predictionItems =
                    predictionsByTarget
                            .getOrDefault(
                                    sessionTarget.getId(),
                                    List.of()
                            )
                            .stream()
                            .sorted(Comparator.comparing(
                                    Prediction::getHorizon
                            ))
                            .map(prediction -> map(
                                    "horizon",
                                    prediction.getHorizon(),
                                    "direction",
                                    prediction.getDirection() == null
                                            ? null
                                            : prediction.getDirection().name(),
                                    "reason",
                                    prediction.getReason()
                            ))
                            .toList();

            targetSnapshots.add(map(
                    "sessionTargetId",
                    sessionTarget.getId().toString(),
                    "marketTargetId",
                    marketTarget.getId().toString(),
                    "code",
                    marketTarget.getCode(),
                    "displayName",
                    marketTarget.getDisplayName(),
                    "marketCategory",
                    marketTarget.getMarketCategory(),
                    "targetType",
                    marketTarget.getTargetType(),
                    "unit",
                    marketTarget.getUnit(),
                    "predictions",
                    predictionItems
            ));
        }

        Map<String, Object> predictionSnapshot =
                map("targets", targetSnapshots);

        List<NewsFact> facts =
                factRepository.findByNewsIdOrderBySortOrderAscIdAsc(
                        news.getId()
                );

        List<Map<String, Object>> factSnapshots =
                facts.stream()
                        .map(fact -> map(
                                "id",
                                fact.getId().toString(),
                                "label",
                                fact.getLabel(),
                                "valueText",
                                fact.getValueText(),
                                "unit",
                                fact.getUnit(),
                                "asOfAt",
                                fact.getAsOfAt() == null
                                        ? null
                                        : fact.getAsOfAt().toString()
                        ))
                        .toList();

        Map<String, Object> baselineSnapshot = null;
        Map<String, Object> resultSnapshot = null;
        Map<String, Object> reflectionSnapshot = null;

        if (includeResults) {
            baselineSnapshot =
                    buildBaselineSnapshot(selectedTargets);

            resultSnapshot =
                    buildResultSnapshot(selectedTargets);

            reflectionSnapshot =
                    buildReflectionSnapshot(sessionId);
        }

        Map<UUID, FeedbackSourceDocument> documents =
                collectDocuments(
                        news.getId(),
                        marketTargetIds,
                        cutoff
                );

        Map<UUID, FeedbackGenerationInput.EvidenceChunk>
                evidenceChunks = new LinkedHashMap<>();

        List<Map<String, Object>> sourceSnapshots =
                buildSourceSnapshots(
                        documents,
                        evidenceChunks
                );

        Map<String, Object> newsSnapshot = map(
                "id",
                news.getId().toString(),
                "title",
                news.getTitle(),
                "category",
                news.getCategory(),
                "briefing",
                news.getBriefing(),
                "publishedAt",
                news.getPublishedAt() == null
                        ? null
                        : news.getPublishedAt().toString(),
                "referenceAt",
                news.getReferenceAt() == null
                        ? null
                        : news.getReferenceAt().toString()
        );

        Map<String, Object> requestSnapshot =
                map(
                        "feedbackStage",
                        includeResults ? "final" : "initial",
                        "inputCutoffAt",
                        cutoff.toString(),
                        "news",
                        newsSnapshot,
                        "facts",
                        factSnapshots,
                        "prediction",
                        predictionSnapshot,
                        "marketBaseline",
                        baselineSnapshot,
                        "marketResult",
                        resultSnapshot,
                        "reflection",
                        reflectionSnapshot,
                        "approvedSources",
                        sourceSnapshots
                );

        String inputHash = hash(requestSnapshot);

        return new FeedbackGenerationInput(
                cutoff,
                requestSnapshot,
                predictionSnapshot,
                baselineSnapshot,
                resultSnapshot,
                reflectionSnapshot,
                inputHash,
                sessionTargetIds,
                evidenceChunks
        );
    }

    private List<SessionTarget> selectedTargets(UUID sessionId) {
        return sessionTargetRepository
                .findAllByLearningSessionId(sessionId)
                .stream()
                .filter(SessionTarget::isSelected)
                .sorted(
                        Comparator
                                .comparing(
                                        SessionTarget::getSortOrder,
                                        Comparator.nullsLast(
                                                Comparator.naturalOrder()
                                        )
                                )
                                .thenComparing(
                                        target ->
                                                target.getId().toString()
                                )
                )
                .toList();
    }

    private Map<String, Object> buildBaselineSnapshot(
            List<SessionTarget> selectedTargets
    ) {
        List<Map<String, Object>> result =
                new ArrayList<>();

        for (SessionTarget target : selectedTargets) {
            SessionTargetBaseline baseline =
                    baselineRepository
                            .findBySessionTargetId(target.getId())
                            .orElse(null);

            if (baseline == null) {
                result.add(map(
                        "sessionTargetId",
                        target.getId().toString(),
                        "baseline",
                        null
                ));
                continue;
            }

            result.add(map(
                    "sessionTargetId",
                    target.getId().toString(),
                    "status",
                    baseline.getStatus().name(),
                    "baselineSessionDate",
                    baseline.getBaselineSessionDate() == null
                            ? null
                            : baseline.getBaselineSessionDate()
                              .toString(),
                    "baselineAt",
                    baseline.getBaselineAt() == null
                            ? null
                            : baseline.getBaselineAt().toString(),
                    "baselineValue",
                    baseline.getBaselineValue(),
                    "unit",
                    baseline.getUnit(),
                    "dataSourceName",
                    baseline.getDataSourceName(),
                    "dataSourceUrl",
                    baseline.getDataSourceUrl(),
                    "unavailableReason",
                    baseline.getUnavailableReason()
            ));
        }

        return map("targets", result);
    }

    private Map<String, Object> buildResultSnapshot(
            List<SessionTarget> selectedTargets
    ) {
        List<Map<String, Object>> targets =
                new ArrayList<>();

        for (SessionTarget target : selectedTargets) {
            List<Map<String, Object>> observationSnapshots =
                    observationRepository
                            .findAllBySessionTargetIdOrderByHorizonAsc(
                                    target.getId()
                            )
                            .stream()
                            .map(observation -> map(
                                    "horizon",
                                    observation.getHorizon(),
                                    "status",
                                    observation.getStatus().name(),
                                    "exceptionStatus",
                                    observation.getExceptionStatus()
                                            .name(),
                                    "expectedObservationDate",
                                    observation.getExpectedObservationDate()
                                            == null
                                            ? null
                                            : observation
                                              .getExpectedObservationDate()
                                              .toString(),
                                    "observationSessionDate",
                                    observation.getObservationSessionDate()
                                            == null
                                            ? null
                                            : observation
                                              .getObservationSessionDate()
                                              .toString(),
                                    "observedAt",
                                    observation.getObservedAt() == null
                                            ? null
                                            : observation
                                              .getObservedAt()
                                              .toString(),
                                    "observedValue",
                                    observation.getObservedValue(),
                                    "changeValue",
                                    observation.getChangeValue(),
                                    "changePercent",
                                    observation.getChangePercent(),
                                    "usableForAiEvidence",
                                    observation
                                            .isUsableForAiEvidence(),
                                    "unit",
                                    observation.getUnit(),
                                    "dataSourceName",
                                    observation.getDataSourceName(),
                                    "dataSourceUrl",
                                    observation.getDataSourceUrl(),
                                    "unavailableReason",
                                    observation.getUnavailableReason()
                            ))
                            .toList();

            targets.add(map(
                    "sessionTargetId",
                    target.getId().toString(),
                    "observations",
                    observationSnapshots
            ));
        }

        return map("targets", targets);
    }

    private Map<String, Object> buildReflectionSnapshot(
            UUID sessionId
    ) {
        List<Map<String, Object>> notes =
                reflectionNoteRepository
                        .findAllByLearningSessionIdOrderByCreatedAtAscIdAsc(
                                sessionId
                        )
                        .stream()
                        .map(note -> map(
                                "id",
                                note.getId().toString(),
                                "sessionTargetId",
                                note.getSessionTargetId() == null
                                        ? null
                                        : note.getSessionTargetId()
                                          .toString(),
                                "reflectionType",
                                note.getReflectionType(),
                                "promptKey",
                                note.getPromptKey().name(),
                                "body",
                                note.getBody(),
                                "createdAt",
                                note.getCreatedAt().toString()
                        ))
                        .toList();

        return map("notes", notes);
    }

    private Map<UUID, FeedbackSourceDocument> collectDocuments(
            UUID newsId,
            List<UUID> marketTargetIds,
            OffsetDateTime cutoff
    ) {
        Map<UUID, FeedbackSourceDocument> result =
                new LinkedHashMap<>();

        documentRepository
                .findEligibleByNewsId(newsId, cutoff)
                .forEach(document ->
                        result.put(document.getId(), document)
                );

        if (!marketTargetIds.isEmpty()) {
            List<UUID> documentIds =
                    documentTargetRepository
                            .findDocumentIdsByMarketTargetIds(
                                    marketTargetIds
                            );

            if (!documentIds.isEmpty()) {
                documentRepository
                        .findEligibleByIds(documentIds, cutoff)
                        .forEach(document ->
                                result.put(
                                        document.getId(),
                                        document
                                )
                        );
            }
        }

        return result;
    }

    private List<Map<String, Object>> buildSourceSnapshots(
            Map<UUID, FeedbackSourceDocument> documents,
            Map<UUID, FeedbackGenerationInput.EvidenceChunk>
                    evidenceChunks
    ) {
        if (documents.isEmpty()) {
            return List.of();
        }

        List<UUID> ids =
                new ArrayList<>(documents.keySet());

        Map<UUID, List<FeedbackSourceDocumentChunk>>
                chunksByDocument =
                chunkRepository
                        .findAllForDocuments(ids)
                        .stream()
                        .collect(Collectors.groupingBy(
                                FeedbackSourceDocumentChunk
                                        ::getSourceDocumentId,
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));

        List<FeedbackSourceDocument> sortedDocuments =
                documents.values()
                        .stream()
                        .sorted(
                                Comparator
                                        .comparing(
                                                FeedbackSourceDocument
                                                        ::getPublishedAt
                                        )
                                        .thenComparing(
                                                document ->
                                                        document.getId()
                                                                .toString()
                                        )
                        )
                        .toList();

        List<Map<String, Object>> result =
                new ArrayList<>();

        for (FeedbackSourceDocument document :
                sortedDocuments) {

            List<Map<String, Object>> chunkSnapshots =
                    new ArrayList<>();

            for (FeedbackSourceDocumentChunk chunk :
                    chunksByDocument.getOrDefault(
                            document.getId(),
                            List.of()
                    )) {

                evidenceChunks.put(
                        chunk.getId(),
                        new FeedbackGenerationInput.EvidenceChunk(
                                chunk.getId(),
                                document.getId(),
                                document.getTitle(),
                                document.getPublisher(),
                                document.getUrl(),
                                document.getPublishedAt().toString(),
                                document.getContentHash(),
                                chunk.getContent()
                        )
                );

                chunkSnapshots.add(map(
                        "chunkId",
                        chunk.getId().toString(),
                        "chunkOrder",
                        chunk.getChunkOrder(),
                        "content",
                        chunk.getContent()
                ));
            }

            result.add(map(
                    "sourceDocumentId",
                    document.getId().toString(),
                    "sourceType",
                    document.getSourceType(),
                    "selectionTier",
                    document.getSelectionTier(),
                    "publisher",
                    document.getPublisher(),
                    "title",
                    document.getTitle(),
                    "url",
                    document.getUrl(),
                    "publishedAt",
                    document.getPublishedAt().toString(),
                    "isPrimary",
                    document.isPrimary(),
                    "contentHash",
                    document.getContentHash(),
                    "chunks",
                    chunkSnapshots
            ));
        }

        return result;
    }

    private String hash(Map<String, Object> requestSnapshot) {
        try {
            byte[] json =
                    jsonMapper.writeValueAsBytes(
                            requestSnapshot
                    );

            byte[] digest =
                    MessageDigest
                            .getInstance("SHA-256")
                            .digest(json);

            return HexFormat.of()
                    .formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to hash AI feedback input.",
                    e
            );
        }
    }

    private static Map<String, Object> map(
            Object... keyValues
    ) {
        Map<String, Object> result =
                new LinkedHashMap<>();

        for (int i = 0; i < keyValues.length; i += 2) {
            result.put(
                    (String) keyValues[i],
                    keyValues[i + 1]
            );
        }

        return result;
    }
}