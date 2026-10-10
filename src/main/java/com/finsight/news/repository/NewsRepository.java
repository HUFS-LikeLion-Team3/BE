package com.finsight.news.repository;

import com.finsight.news.entity.News;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface NewsRepository extends JpaRepository<News, UUID> {
    Optional<News> findByIdAndStatus(UUID id, String status);
}
