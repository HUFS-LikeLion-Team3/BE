package com.finsight.market.service;

import com.finsight.market.entity.MarketTarget;
import com.finsight.market.dto.MarketTargetResponses.ReferenceMarket;
import com.finsight.market.service.ReferenceMarketProvider.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class ReferenceMarketService {
    private final ReferenceMarketProvider provider;

    public ReferenceMarket get(MarketTarget target) {
        Snapshot snapshot = provider.latestRegularCloses(target);
        if (snapshot == null || snapshot.status() == null) {
            return missing(target, "unavailable", null, null, "확정 정규 종가를 확인할 수 없습니다.");
        }
        if (snapshot.status() != Status.ready) {
            return missing(target, snapshot.status().name(), snapshot.dataSourceName(),
                    snapshot.dataSourceUrl(), snapshot.unavailableReason());
        }
        if (snapshot.sessionDate() == null || snapshot.asOfAt() == null
                || snapshot.value() == null || snapshot.previousCloseValue() == null) {
            return missing(target, "unavailable", snapshot.dataSourceName(),
                    snapshot.dataSourceUrl(), "확정 정규 종가 또는 직전 정규 종가가 없습니다.");
        }
        BigDecimal change = snapshot.value().subtract(snapshot.previousCloseValue());
        BigDecimal percent = snapshot.previousCloseValue().signum() == 0 ? null
                : change.multiply(BigDecimal.valueOf(100))
                        .divide(snapshot.previousCloseValue(), 6, RoundingMode.HALF_UP);
        return new ReferenceMarket("ready", snapshot.sessionDate(), snapshot.asOfAt(),
                snapshot.value(), snapshot.previousCloseValue(), change, percent, target.getUnit(),
                snapshot.dataSourceName(), snapshot.dataSourceUrl(), null);
    }

    private ReferenceMarket missing(MarketTarget target, String status, String source, String url, String reason) {
        return new ReferenceMarket(status, null, null, null, null, null, null,
                target.getUnit(), source, url, reason);
    }
}
