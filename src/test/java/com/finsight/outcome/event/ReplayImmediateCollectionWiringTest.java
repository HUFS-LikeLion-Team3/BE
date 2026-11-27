package com.finsight.outcome.event;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finsight_replay_collection;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "finsight.outcome.collection.enabled=true",
        "finsight.outcome.collection.initial-delay-ms=600000",
        "finsight.outcome.collection.daily-initial-delay-ms=600000",
        "finsight.outcome.collection.observation-initial-delay-ms=600000",
        "finsight.outcome.collection.result-initial-delay-ms=600000",
        "finsight.outcome.collection.series-initial-delay-ms=600000"
})
class ReplayImmediateCollectionWiringTest {
    @Autowired ReplayImmediateCollectionListener listener;
    @Autowired @Qualifier("outcomeTaskExecutor") Executor executor;

    @Test
    void listenerAndExecutorLoadWhenCollectionIsEnabled() {
        assertNotNull(listener);
        assertNotNull(executor);
    }
}
