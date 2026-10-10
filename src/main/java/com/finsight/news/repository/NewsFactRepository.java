package com.finsight.news.repository;

import com.finsight.news.entity.NewsFact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface NewsFactRepository extends JpaRepository<NewsFact, UUID> {
    List<NewsFact> findByNewsIdOrderBySortOrderAscIdAsc(UUID newsId);
}
