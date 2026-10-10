package com.finsight.news.repository;

import com.finsight.news.entity.NewsSource;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface NewsSourceRepository extends JpaRepository<NewsSource, UUID> {
    @Query("select s from NewsSource s join fetch s.document where s.newsId = :newsId order by s.id")
    List<NewsSource> findSources(UUID newsId);
}
