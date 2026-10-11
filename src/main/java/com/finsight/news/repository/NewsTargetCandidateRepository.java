package com.finsight.news.repository;

import com.finsight.news.entity.NewsTargetCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface NewsTargetCandidateRepository extends JpaRepository<NewsTargetCandidate, NewsTargetCandidate.Key> {
    @Query("select c from NewsTargetCandidate c join fetch c.target where c.news.id = :newsId order by c.sortOrder asc")
    List<NewsTargetCandidate> findCandidates(@Param("newsId") UUID newsId);
}
