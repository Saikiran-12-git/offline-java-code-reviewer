package dev.saikiran.reviewer;

import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Prevent unrelated browser pages from submitting reviews to this local service. */
@Component
public class LocalOriginFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String origin = exchange.getRequest().getHeaders().getOrigin();
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                int originPort = uri.getPort() < 0 ? 80 : uri.getPort();
                int localPort = exchange.getRequest().getLocalAddress().getPort();
                if (!"http".equals(uri.getScheme()) || !Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost())
                        || originPort != localPort || uri.getUserInfo() != null) {
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                    return exchange.getResponse().setComplete();
                }
            } catch (RuntimeException invalid) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
        }
        return chain.filter(exchange);
    }
}
