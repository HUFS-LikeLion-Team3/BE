package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:issue27_dispatch;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@Import(AiRagDispatchLeaseService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiRagDispatchLeaseIntegrationTest {
    @Autowired private AiRagDispatchLeaseService lease;
    @Autowired private AiFeedbackRepository feedbackRepository;
    @Autowired private AiRagJobRepository jobRepository;

    @Test
    void concurrentWorkersGetExactlyOneDispatchLease() throws Exception {
        UUID sessionId = UUID.randomUUID();
        String requestId = "feedback:initial:" + sessionId + ":v1";
        AiFeedback feedback = feedbackRepository.saveAndFlush(AiFeedback.create(
                sessionId, AiFeedback.FeedbackStage.INITIAL, 1, requestId,
                "feedback-p0-v1", "feedback-policy-p0-v1", OffsetDateTime.now()));
        UUID feedbackId = feedback.getId();
        jobRepository.saveAndFlush(AiRagJob.create(feedbackId, sessionId, "initial",
                requestId, "sha256:" + "b".repeat(64), "{\"frozen\":true}"));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<UUID> first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return lease.tryClaim(feedbackId);
            });
            Future<UUID> second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return lease.tryClaim(feedbackId);
            });
            start.countDown();
            UUID a = first.get(10, TimeUnit.SECONDS);
            UUID b = second.get(10, TimeUnit.SECONDS);
            assertTrue((a == null) != (b == null), "exactly one worker owns the lease");
            assertEquals(AiFeedback.FeedbackStatus.generating,
                    feedbackRepository.findById(feedbackId).orElseThrow().getStatus());
            UUID owner = a == null ? b : a;
            assertEquals(owner.toString(),
                    jobRepository.findById(feedbackId).orElseThrow().getDispatchOwner());
            lease.release(feedbackId, owner);
            assertNull(jobRepository.findById(feedbackId).orElseThrow().getDispatchOwner());
        }
    }
}
