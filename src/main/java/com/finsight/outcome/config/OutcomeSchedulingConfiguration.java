package com.finsight.outcome.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "finsight.outcome.collection.enabled",
        havingValue = "true"
)
public class OutcomeSchedulingConfiguration {

    /** Replay 제출 직후 수집 전용. AI 피드백 스레드와 섞지 않는다. */
    @Bean("outcomeTaskExecutor")
    public Executor outcomeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("outcome-");
        executor.initialize();
        return executor;
    }
}
