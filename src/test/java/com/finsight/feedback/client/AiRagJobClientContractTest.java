package com.finsight.feedback.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiRagJobClientContractTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AiRagJobClient client() {
        return new AiRagJobClient(
                "http://127.0.0.1:"
                        + server.getAddress().getPort(),
                "test-token",
                HttpClient.newHttpClient(),
                JsonMapper.builder().build()
        );
    }

    @Test
    void queryByOriginalRequestIdDoesNotPostAndUsesBearerToken()
            throws Exception {

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        AtomicReference<String> method =
                new AtomicReference<>();

        AtomicReference<String> auth =
                new AtomicReference<>();

        AtomicReference<String> query =
                new AtomicReference<>();

        UUID jobId = UUID.randomUUID();

        server.createContext(
                "/internal/v1/jobs",
                exchange -> {
                    method.set(
                            exchange.getRequestMethod()
                    );

                    auth.set(
                            exchange.getRequestHeaders()
                                    .getFirst("Authorization")
                    );

                    query.set(
                            exchange.getRequestURI()
                                    .getRawQuery()
                    );

                    byte[] body = (
                            "{\"jobId\":\""
                                    + jobId
                                    + "\",\"status\":\"awaiting_review\"}"
                    ).getBytes(StandardCharsets.UTF_8);

                    exchange.sendResponseHeaders(
                            200,
                            body.length
                    );

                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
        );

        server.start();

        Map<String, Object> result =
                client().findByBeRequestId(
                        "feedback:initial:abc:v1"
                );

        assertEquals("GET", method.get());
        assertEquals("Bearer test-token", auth.get());

        assertTrue(
                query.get().startsWith("beRequestId=")
        );

        assertEquals(
                jobId.toString(),
                result.get("jobId")
        );
    }

    @Test
    void errorFieldUsesTheActualAiContractShape()
            throws Exception {

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        server.createContext(
                "/internal/v1/jobs",
                exchange -> {
                    byte[] body =
                            "{\"error\":\"package_not_registered\"}"
                                    .getBytes(StandardCharsets.UTF_8);

                    exchange.sendResponseHeaders(
                            503,
                            body.length
                    );

                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
        );

        server.start();

        var error = assertThrows(
                AiRagJobClient.AiRagHttpException.class,
                () -> client().findByBeRequestId("id")
        );

        assertEquals(503, error.getStatusCode());

        assertEquals(
                "package_not_registered",
                error.getErrorCode()
        );
    }

    @Test
    void resultGetIsSeparateAndReturnsUnapproved409()
            throws Exception {

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        server.createContext(
                "/internal/v1/jobs",
                exchange -> {
                    assertEquals(
                            "GET",
                            exchange.getRequestMethod()
                    );

                    assertTrue(
                            exchange.getRequestURI()
                                    .getPath()
                                    .endsWith("/result")
                    );

                    byte[] body =
                            "{\"error\":\"result_not_ready\"}"
                                    .getBytes(StandardCharsets.UTF_8);

                    exchange.sendResponseHeaders(
                            409,
                            body.length
                    );

                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
        );

        server.start();

        var error = assertThrows(
                AiRagJobClient.AiRagHttpException.class,
                () -> client().getApprovedResult(
                        UUID.randomUUID()
                )
        );

        assertEquals(409, error.getStatusCode());

        assertEquals(
                "result_not_ready",
                error.getErrorCode()
        );
    }
}