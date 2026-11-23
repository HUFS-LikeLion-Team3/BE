package com.finsight.market.service;

import com.finsight.market.data.MarketDataService;
import com.finsight.market.entity.MarketTarget;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LiveReferenceMarketProvider implements ReferenceMarketProvider {
    private final MarketDataService marketData;

    @Override public Snapshot latestRegularCloses(MarketTarget target) {
        var result = marketData.latestRegularCloses(target);
        if (result.status() != com.finsight.market.data.MarketDataProvider.Status.ready) {
            return new Snapshot(Status.valueOf(result.status().name()), null, null, null, null,
                    null, null, null);
        }
        var pair = result.data();
        return new Snapshot(Status.ready, pair.latest().sessionDate(), pair.latest().asOfAt(),
                pair.latest().value(), pair.previous().value(), pair.latest().dataSourceName(),
                pair.latest().dataSourceUrl(), null);
    }
}
