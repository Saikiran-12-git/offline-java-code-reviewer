package dev.saikiran.reviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewIntegrationTest {
    static final AtomicReference<String> reply = new AtomicReference<>("");
    static final AtomicReference<Integer> status = new AtomicReference<>(200);
    static final AtomicReference<String> captured = new AtomicReference<>();
    static final HttpServer ollama = startMock();
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    static HttpServer startMock() {
        try {
            HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/chat", exchange -> {
                captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] data = reply.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(status.get(), data.length);
                exchange.getResponseBody().write(data);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("review.ollama-url", () -> "http://127.0.0.1:" + ollama.getAddress().getPort());
    }
    @AfterAll static void stop() { ollama.stop(0); }
    WebTestClient client() { return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build(); }
    WebTestClient.ResponseSpec review(String code, String focus) {
        return client().post().uri("/api/reviews").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(java.util.Map.of("code", code, "focus", focus)).exchange();
    }
    @Test void streamsReviewAndUsesLocalModelWithBoundedContext() throws Exception {
        status.set(200);
        reply.set("{\"message\":{\"content\":\"Check null inputs.\"},\"done\":false}\n{\"message\":{\"content\":\" Add tests.\"},\"done\":true}\n");
        review("class Example {}", "BUGS").expectStatus().isOk().expectBodyList(ReviewEvent.class)
            .value(events -> {
                assertThat(events).extracting(ReviewEvent::type).containsExactly("delta", "done");
                assertThat(events.get(1).text()).isEqualTo(" Add tests.");
            });
        JsonNode request = mapper.readTree(captured.get());
        assertThat(request.path("stream").asBoolean()).isTrue();
        assertThat(request.path("model").asText()).isEqualTo("qwen2.5-coder:3b");
        assertThat(request.path("options").path("num_ctx").asInt()).isEqualTo(8192);
        assertThat(request.path("messages").get(0).path("content").asText()).contains("untrusted data");
        assertThat(request.path("messages").get(1).path("content").asText()).contains("class Example {}");
    }
    @Test void rejectsEmptyOversizedAndInvalidFocusInputs() {
        review(" ", "BUGS").expectStatus().isBadRequest();
        review("x".repeat(6001), "BUGS").expectStatus().isBadRequest();
        review("é".repeat(3100), "BUGS").expectStatus().isBadRequest();
        review("class X {}", "UNKNOWN").expectStatus().isBadRequest();
        client().post().uri("/api/reviews").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"code\":\"class X {}\"}").exchange().expectStatus().isBadRequest();
    }
    @Test void upstreamHttpErrorBecomesUsefulStreamError() {
        status.set(404); reply.set("{\"error\":\"model not found\"}\n");
        review("class X {}", "SECURITY").expectStatus().isOk().expectBodyList(ReviewEvent.class)
            .value(events -> assertThat(events.getFirst().text()).contains("HTTP 404"));
        status.set(200);
    }
    @Test void missingDoneIsNotReportedAsSuccess() {
        status.set(200); reply.set("{\"message\":{\"content\":\"Partial\"},\"done\":false}\n");
        review("class X {}", "READABILITY").expectStatus().isOk().expectBodyList(ReviewEvent.class)
            .value(events -> assertThat(events).extracting(ReviewEvent::type).containsExactly("delta", "error"));
    }
    @Test void malformedAndErrorFramesProduceErrors() {
        status.set(200); reply.set("not-json\n");
        review("class X {}", "BUGS").expectBodyList(ReviewEvent.class)
            .value(events -> assertThat(events.getLast().type()).isEqualTo("error"));
        reply.set("{\"error\":\"private internal information\"}\n");
        review("class X {}", "BUGS").expectBodyList(ReviewEvent.class)
            .value(events -> assertThat(events.getLast().text()).doesNotContain("private internal information"));
    }
    @Test void rejectsUnrelatedBrowserOrigins() {
        client().post().uri("/api/reviews").header("Origin", "https://example.com")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(java.util.Map.of("code", "class X {}", "focus", "BUGS"))
            .exchange().expectStatus().isForbidden();
    }
    @Test void servesLocalInterface() {
        client().get().uri("/").exchange().expectStatus().isOk().expectBody(String.class)
            .value(html -> assertThat(html).contains("LOCAL LENS", "Review code"));
    }
}
