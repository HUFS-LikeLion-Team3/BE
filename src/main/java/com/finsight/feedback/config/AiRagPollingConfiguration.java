package com.finsight.feedback.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Explicit opt-in: polling must not run just because unit tests/DB are present. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "finsight.ai.rag-polling", name = "enabled", havingValue = "true")
public class AiRagPollingConfiguration { }
