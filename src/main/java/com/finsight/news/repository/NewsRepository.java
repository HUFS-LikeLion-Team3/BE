package com.finsight.news.repository;

import com.finsight.news.entity.News;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface NewsRepository extends JpaRepository<News, UUID> {
    Optional<News> findByIdAndStatus(UUID id, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from News n where n.id = :id")
    Optional<News> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select n from News n where n.id = :id and n.status = 'published'")
    Optional<News> findPublishedForSessionCreation(@Param("id") UUID id);

    Page<News> findAllByStatus(String status, Pageable pageable);
}
