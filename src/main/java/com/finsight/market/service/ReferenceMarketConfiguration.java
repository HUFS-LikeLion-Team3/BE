package com.finsight.market.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.finsight.market.service.ReferenceMarketProvider.*;

@Configuration
public class ReferenceMarketConfiguration {
    @Bean
    @ConditionalOnMissingBean(ReferenceMarketProvider.class)
    ReferenceMarketProvider unavailableReferenceMarketProvider() {
        return target -> new Snapshot(Status.unavailable, null, null, null, null,
                null, null, "시장 데이터 제공처가 연결되지 않았습니다.");
    }
}
