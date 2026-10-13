package dev.saikiran.reviewer;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class ReviewController {
    private final ReviewService service;
    public ReviewController(ReviewService service) { this.service = service; }

    @PostMapping(value = "/reviews", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<ReviewEvent> review(@Valid @RequestBody ReviewRequest request) {
        return service.review(request);
    }
}
