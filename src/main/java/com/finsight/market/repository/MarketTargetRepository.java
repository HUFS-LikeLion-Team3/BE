package com.finsight.market.repository;

import com.finsight.market.entity.MarketTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface MarketTargetRepository extends JpaRepository<MarketTarget, UUID> {
    @Query("select t from MarketTarget t where t.active = true "
            + "and (:category is null or t.marketCategory = :category) "
            + "and (:type is null or t.targetType = :type) order by t.code asc")
    List<MarketTarget> findActive(@Param("category") String category, @Param("type") String type);
}
