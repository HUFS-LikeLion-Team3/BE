package com.finsight.feedback.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiRagJobDeliveryClientTest {
    @Test
    void deliverySendsContractJsonAndBearerTokenOnlyToExactJobPath() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        UUID jobId = UUID.fromString("66666666-6666-4666-8666-666666666666");
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        String path = "/internal/v1/jobs/" + jobId + "/delivery";
        server.createContext(path, exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            AiRagJobClient client = new AiRagJobClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "private-token", HttpClient.newHttpClient(), JsonMapper.builder().build());
            client.reportDelivery(jobId, "stored", "a".repeat(64), "be-issue26:stored:test");
            assertEquals("POST", method.get());
            assertEquals("Bearer private-token", auth.get());
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = JsonMapper.builder().build().readValue(body.get(), Map.class);
            assertEquals(Map.of("status", "stored", "resultHash", "a".repeat(64),
                    "notes", "be-issue26:stored:test"), sent);
            assertThrows(IllegalArgumentException.class, () -> client.reportDelivery(jobId,
                    "ready", "a".repeat(64), "receipt"));
            assertThrows(IllegalArgumentException.class, () -> client.reportDelivery(jobId,
                    "stored", "sha256:" + "a".repeat(64), "receipt"));
        } finally {
            server.stop(0);
        }
    }
}
