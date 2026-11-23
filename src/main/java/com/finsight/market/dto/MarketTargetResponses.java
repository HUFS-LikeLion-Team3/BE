package com.finsight.market.dto;

import com.finsight.market.entity.MarketTarget;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public final class MarketTargetResponses {
    private MarketTargetResponses() {}

    public record ListResponse(List<Target> targets) {}

    public record Detail(UUID id, String code, String displayName, String marketCategory,
                         String targetType, Map<String, String> directionLabels, String unit,
                         boolean isActive, ReferenceMarket referenceMarket) {}

    public record ReferenceMarket(String status, LocalDate sessionDate, OffsetDateTime asOfAt,
                                  BigDecimal value, BigDecimal previousCloseValue,
                                  BigDecimal changeValue, BigDecimal changePercent, String unit,
                                  String dataSourceName, String dataSourceUrl, String unavailableReason) {}

    public record Target(UUID id, String code, String displayName, String marketCategory,
                         String targetType, Map<String, String> directionLabels, String unit) {
        public static Target from(MarketTarget target) {
            return new Target(target.getId(), target.getCode(), target.getDisplayName(),
                    target.getMarketCategory(), target.getTargetType(),
                    target.getDirectionLabels(), target.getUnit());
        }
    }
}
