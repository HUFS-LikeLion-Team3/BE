package com.finsight.feedback.entity;

import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiFeedbackReferenceMappingTest {

    @Test
    void chunkMustNotBeUniqueWithinFeedbackButOrderRemainsUnique() {
        Table table = AiFeedbackReference.class.getAnnotation(Table.class);

        assertFalse(Arrays.stream(table.uniqueConstraints())
                .anyMatch(constraint -> Arrays.asList(constraint.columnNames())
                        .contains("source_document_chunk_id")));

        assertTrue(Arrays.stream(table.uniqueConstraints())
                .anyMatch(constraint -> Arrays.asList(constraint.columnNames())
                        .containsAll(java.util.List.of("ai_feedback_id", "sort_order"))));

        assertTrue(Arrays.stream(table.indexes())
                .anyMatch(index ->
                        index.columnList().equals("ai_feedback_id,source_document_chunk_id")
                                && !index.unique()));
    }

    @Test
    void differentCitationsCanReferenceTheSameChunkWithoutBeingMerged() {
        UUID feedbackId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();

        AiFeedbackReference first = AiFeedbackReference.create(
                feedbackId, documentId, chunkId, "근거 1",
                "실제 인용 A", "뉴스 흐름 해설", 1);
        AiFeedbackReference second = AiFeedbackReference.create(
                feedbackId, documentId, chunkId, "근거 2",
                "실제 인용 B", "반대 시나리오", 2);

        assertEquals(first.getSourceDocumentChunkId(), second.getSourceDocumentChunkId());
        assertEquals("실제 인용 A", first.getCitationExcerpt());
        assertEquals("실제 인용 B", second.getCitationExcerpt());
        assertEquals("뉴스 흐름 해설", first.getUsageContext());
        assertEquals("반대 시나리오", second.getUsageContext());
        assertEquals(1, first.getSortOrder());
        assertEquals(2, second.getSortOrder());
    }
}
