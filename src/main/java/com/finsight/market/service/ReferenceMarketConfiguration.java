package com.finsight.market.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.finsight.market.data.MarketDataService;

@Configuration
public class ReferenceMarketConfiguration {
    @Bean
    ReferenceMarketProvider referenceMarketProvider(MarketDataService marketData) {
        return new LiveReferenceMarketProvider(marketData);
    }
}
