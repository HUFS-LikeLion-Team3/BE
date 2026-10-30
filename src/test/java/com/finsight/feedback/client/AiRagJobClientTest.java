package com.finsight.feedback.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiRagJobClientTest {

    private HttpServer server;

    private static final String REQUEST_JSON = """
            {
              "beRequestId": "feedback:initial:test-session:v1",
              "feedbackId": "11111111-1111-4111-8111-111111111111",
              "beInputHash": "test-input-hash",
              "packageId": "approved-package-v1",
              "newsId": "22222222-2222-4222-8222-222222222222",
              "bindings": {
                "33333333-3333-4333-8333-333333333333":
                "44444444-4444-4444-8444-444444444444"
              },
              "input": {
                "stage": "initial",
                "sessionId": "55555555-5555-4555-8555-555555555555",
                "submittedAt": "2026-10-27T10:00:00+09:00",
                "news": {
                  "title": "Test news",
                  "briefing": "Test briefing",
                  "facts": []
                },
                "predictions": [],
                "policyVersion": "feedback-policy-v1"
              }
            }
            """;

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AiRagJobClient client() {
        return new AiRagJobClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-service-token",
                HttpClient.newHttpClient(),
                JsonMapper.builder().build()
        );
    }

    @Test
    void postsFrozenEnvelopeWithServiceBearerToken() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();

        server.createContext("/internal/v1/jobs", exchange -> {
            receivedBody.set(
                    new String(
                            exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8
                    )
            );

            authorization.set(
                    exchange.getRequestHeaders()
                            .getFirst("Authorization")
            );

            contentType.set(
                    exchange.getRequestHeaders()
                            .getFirst("Content-Type")
            );

            byte[] response = """
                    {
                      "jobId": "66666666-6666-4666-8666-666666666666",
                      "status": "accepted"
                    }
                    """.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders()
                    .set("Content-Type", "application/json");

            exchange.sendResponseHeaders(202, response.length);

            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });

        server.start();

        Map<String, Object> result =
                client().submitFrozenJob(REQUEST_JSON);

        assertEquals(REQUEST_JSON, receivedBody.get());
        assertEquals(
                "Bearer test-service-token",
                authorization.get()
        );
        assertEquals(
                "application/json",
                contentType.get()
        );

        assertEquals(
                "66666666-6666-4666-8666-666666666666",
                result.get("jobId")
        );
        assertEquals("accepted", result.get("status"));
    }

    @Test
    void statusIsReadUsingGetWithoutSubmittingNewJob() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        AtomicReference<String> receivedMethod =
                new AtomicReference<>();

        server.createContext("/internal/v1/jobs/", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());

            byte[] response = """
                    {
                      "jobId": "66666666-6666-4666-8666-666666666666",
                      "status": "awaiting_initial"
                    }
                    """.getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(200, response.length);

            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });

        server.start();

        Map<String, Object> result =
                client().getJobResource(
                        "/internal/v1/jobs/"
                                + "66666666-6666-4666-8666-666666666666"
                );

        assertEquals("GET", receivedMethod.get());
        assertEquals("awaiting_initial", result.get("status"));
    }

    @Test
    void preservesHttpErrorCodeWithoutExposingToken() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        server.createContext("/internal/v1/jobs", exchange -> {
            byte[] response = """
                    {
                      "code": "package_not_registered"
                    }
                    """.getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(503, response.length);

            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });

        server.start();

        AiRagJobClient.AiRagHttpException error =
                assertThrows(
                        AiRagJobClient.AiRagHttpException.class,
                        () -> client().submitFrozenJob(REQUEST_JSON)
                );

        assertEquals(503, error.getStatusCode());

        assertEquals(
                "package_not_registered",
                error.getErrorCode()
        );

        assertFalse(
                error.getMessage().contains("test-service-token")
        );
    }

    @Test
    void rejectsFinalWithoutInitialJobId() {
        String invalidFinal = REQUEST_JSON.replace(
                "\"stage\": \"initial\"",
                "\"stage\": \"final\""
        );

        AiRagJobClient client = new AiRagJobClient(
                "http://127.0.0.1:9999",
                "test-service-token",
                HttpClient.newHttpClient(),
                JsonMapper.builder().build()
        );

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.submitFrozenJob(invalidFinal)
                );

        assertTrue(
                error.getMessage().contains("initialJobId")
        );
    }
}