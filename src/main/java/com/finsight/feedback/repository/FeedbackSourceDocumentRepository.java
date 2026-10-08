package com.finsight.feedback.repository;

import com.finsight.feedback.entity.FeedbackSourceDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FeedbackSourceDocumentRepository
        extends JpaRepository<FeedbackSourceDocument, UUID> {
}