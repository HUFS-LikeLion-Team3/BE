package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedbackItem;
import com.finsight.feedback.entity.AiFeedbackItemReference;
import com.finsight.feedback.entity.AiFeedbackReference;
import com.finsight.feedback.entity.FeedbackSourceDocumentChunk;
import com.finsight.feedback.repository.AiFeedbackItemReferenceRepository;
import com.finsight.feedback.repository.AiFeedbackItemRepository;
import com.finsight.feedback.repository.AiFeedbackReferenceRepository;
import com.finsight.feedback.repository.FeedbackSourceDocumentChunkRepository;
import com.finsight.feedback.repository.FeedbackSourceDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Writes already-approved RAG citations into the existing reference/link tables.
 *
 * Invoke ONLY after the approved response is durably captured and the package,
 * hashes, and references have been verified. The caller must save feedback items
 * and call this writer within the SAME transaction; it must not mark ready itself.
 *
 * RAG reference IDs are scoped to one approved result. The mapping to generated
 * BE reference PKs is deliberately not keyed by chunk ID.
 */
@Service
@RequiredArgsConstructor
public class ApprovedCitationWriter {

    private final AiFeedbackReferenceRepository referenceRepository;
    private final AiFeedbackItemReferenceRepository itemReferenceRepository;
    private final AiFeedbackItemRepository itemRepository;
    private final FeedbackSourceDocumentRepository documentRepository;
    private final FeedbackSourceDocumentChunkRepository chunkRepository;

    /** Raw RAG IDs and verbatim citation contents; no excerpt synthesis or truncation. */
    public record Citation(
            String referenceId,
            UUID sourceDocumentId,
            UUID chunkId,
            String citationExcerpt,
            String usageContext
    ) {
    }

    /** BE item PK created in the same transaction and RAG reference IDs in their order. */
    public record ItemLinks(UUID itemId, List<String> referenceIds) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, UUID> write(
            UUID feedbackId,
            List<Citation> citations,
            List<ItemLinks> itemLinks
    ) {
        if (feedbackId == null || citations == null || itemLinks == null) {
            throw new IllegalArgumentException("Approved citation arguments are missing");
        }

        // Validate every link, item ownership, document and chunk BEFORE writing.
        // Any later DB failure is rolled back by the caller's transaction.
        Map<String, Citation> sourceByReferenceId = new LinkedHashMap<>();
        Set<UUID> documentIds = new HashSet<>();
        Set<UUID> chunkIds = new HashSet<>();

        for (Citation citation : citations) {
            if (citation == null
                    || citation.referenceId() == null
                    || citation.referenceId().isBlank()
                    || citation.sourceDocumentId() == null
                    || citation.chunkId() == null
                    || citation.citationExcerpt() == null
                    || citation.citationExcerpt().isBlank()) {
                throw new IllegalStateException("Approved RAG citation is incomplete");
            }
            if (sourceByReferenceId.putIfAbsent(citation.referenceId(), citation) != null) {
                throw new IllegalStateException(
                        "Duplicate approved RAG reference ID: " + citation.referenceId());
            }
            documentIds.add(citation.sourceDocumentId());
            chunkIds.add(citation.chunkId());
        }

        Set<UUID> linkedItemIds = new HashSet<>();
        for (ItemLinks links : itemLinks) {
            if (links == null || links.itemId() == null || links.referenceIds() == null) {
                throw new IllegalStateException("Approved item links are incomplete");
            }
            if (!linkedItemIds.add(links.itemId())) {
                throw new IllegalStateException("Duplicate approved item link list");
            }
            Set<String> seen = new HashSet<>();
            for (String referenceId : links.referenceIds()) {
                if (referenceId == null
                        || !sourceByReferenceId.containsKey(referenceId)
                        || !seen.add(referenceId)) {
                    throw new IllegalStateException(
                            "Unknown or duplicate approved RAG reference ID: " + referenceId);
                }
            }
        }

        if (!linkedItemIds.isEmpty()) {
            Map<UUID, AiFeedbackItem> items = itemRepository.findAllById(linkedItemIds)
                    .stream().collect(Collectors.toMap(AiFeedbackItem::getId, item -> item));
            for (UUID itemId : linkedItemIds) {
                AiFeedbackItem item = items.get(itemId);
                if (item == null || !feedbackId.equals(item.getAiFeedbackId())) {
                    throw new IllegalStateException(
                            "Approved item is missing or belongs to another feedback: " + itemId);
                }
            }
        }

        if (!documentIds.isEmpty()) {
            Set<UUID> foundDocumentIds = documentRepository.findAllById(documentIds)
                    .stream().map(document -> document.getId())
                    .collect(Collectors.toSet());
            if (!foundDocumentIds.containsAll(documentIds)) {
                throw new IllegalStateException("Approved source document is missing");
            }

            Map<UUID, FeedbackSourceDocumentChunk> chunks = chunkRepository.findAllById(chunkIds)
                    .stream().collect(Collectors.toMap(FeedbackSourceDocumentChunk::getId, c -> c));
            for (Citation citation : citations) {
                FeedbackSourceDocumentChunk chunk = chunks.get(citation.chunkId());
                if (chunk == null || !citation.sourceDocumentId().equals(chunk.getSourceDocumentId())) {
                    throw new IllegalStateException(
                            "Approved source chunk is missing or does not belong to document");
                }
            }
        }

        Map<String, UUID> beReferenceIds = new LinkedHashMap<>();
        int sortOrder = 0;
        for (Citation citation : citations) {
            int order = ++sortOrder;
            AiFeedbackReference saved = referenceRepository.save(
                    AiFeedbackReference.create(
                            feedbackId,
                            citation.sourceDocumentId(),
                            citation.chunkId(),
                            "근거 " + order,
                            citation.citationExcerpt(),
                            citation.usageContext(),
                            order
                    )
            );
            beReferenceIds.put(citation.referenceId(), saved.getId());
        }

        for (ItemLinks item : itemLinks) {
            for (String referenceId : item.referenceIds()) {
                itemReferenceRepository.save(
                        AiFeedbackItemReference.create(
                                item.itemId(), beReferenceIds.get(referenceId))
                );
            }
        }

        return Map.copyOf(beReferenceIds);
    }
}
