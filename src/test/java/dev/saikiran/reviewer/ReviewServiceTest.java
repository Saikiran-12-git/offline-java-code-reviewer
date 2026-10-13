package dev.saikiran.reviewer;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewServiceTest {
    @Test void refusesExternalEndpointsAndCloudModelNames() {
        for (String url : new String[]{"https://ollama.com", "http://example.com", "http://127.0.0.1@evil.com", "http://localhost/forward"}) {
            assertThatThrownBy(() -> new ReviewService(WebClient.builder(), url, "local", 1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ReviewService(WebClient.builder(), "http://127.0.0.1:11434", "model:cloud", 1, 2))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void timesOutAndReleasesSlot() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.never());
        ReviewService service = new ReviewService(builder, "http://127.0.0.1:11434", "local", 1, 3);
        ReviewRequest input = new ReviewRequest("class X {}", ReviewRequest.Focus.BUGS);
        StepVerifier.withVirtualTime(() -> service.review(input))
            .thenAwait(java.time.Duration.ofSeconds(2))
            .expectNextMatches(event -> event.type().equals("error") && event.text().contains("timed out"))
            .verifyComplete();
        StepVerifier.withVirtualTime(() -> service.review(input))
            .thenAwait(java.time.Duration.ofSeconds(2))
            .expectNextMatches(event -> event.text().contains("timed out")).verifyComplete();
    }
    @Test void cancellationReleasesConcurrencySlot() {
        ReviewService service = new ReviewService(WebClient.builder().exchangeFunction(request -> Mono.never()),
            "http://127.0.0.1:11434", "local", 10, 30);
        ReviewRequest input = new ReviewRequest("class X {}", ReviewRequest.Focus.BUGS);
        var subscription = service.review(input).subscribe();
        StepVerifier.create(service.review(input))
            .expectNextMatches(event -> event.text().contains("Another review")) .verifyComplete();
        subscription.dispose();
        StepVerifier.create(service.review(input)).thenCancel().verify();
    }
    @Test void enforcesTotalDeadlineEvenWithoutIdleTimeout() {
        ReviewService service = new ReviewService(WebClient.builder().exchangeFunction(request -> Mono.never()),
            "http://127.0.0.1:11434", "local", 20, 1);
        StepVerifier.withVirtualTime(() -> service.review(new ReviewRequest("class X {}", ReviewRequest.Focus.BUGS)))
            .thenAwait(java.time.Duration.ofSeconds(2))
            .expectNextMatches(event -> event.type().equals("error") && event.text().contains("time limit"))
            .verifyComplete();
    }
}
